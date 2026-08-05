package com.skala.imlate.registration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.skala.imlate.common.error.ApiException;
import com.skala.imlate.common.error.ErrorCode;
import com.skala.imlate.registration.domain.ReturnRegistration;
import com.skala.imlate.registration.domain.ReturnRegistrationRepository;
import com.skala.imlate.registration.wal.RegistrationWalRepository;
import com.skala.imlate.support.TestFixtures;

/**
 * {@link RegistrationService#cancel(CancelCommand)} 단위 테스트.
 *
 * <p>취소는 <b>남의 등록을 지울 수 있는 기능</b>이라 등록보다 방어가 중요하다.
 * 명단에서 빠진 교육생은 22:30 에 문이 잠기면 밖에서 밤을 새게 되므로,
 * "잘못 취소되는 것"이 "취소가 안 되는 것"보다 훨씬 나쁜 실패다. 그 관점에서 다음을 못 박는다.
 *
 * <ul>
 *   <li>비밀번호가 틀리면 절대 취소되지 않는다.</li>
 *   <li>실패 사유(등록 없음 / 비밀번호 틀림)를 구분해 알려주지 않는다 — 응답 차이로
 *       "오늘 누가 등록했는지"가 새어 나가면 안 된다.</li>
 *   <li>시도 횟수를 다 쓰면 비밀번호를 <b>보기도 전에</b> 막는다(대입 방지의 핵심).</li>
 *   <li>마감(21:45) 뒤에는 취소할 수 없다 — 명단은 이미 사감에게 나갔다.</li>
 * </ul>
 */
@DisplayName("등록 취소(RegistrationService.cancel)")
class RegistrationCancelTest {

    private ReturnRegistrationRepository registrationRepository;
    private RegistrationWriter registrationWriter;
    private RegistrationWindowPolicy windowPolicy;
    private CancelAttemptGuard cancelAttemptGuard;
    private RegistrationService service;

    private static final String PERSON_KEY = "person-hash";

    @BeforeEach
    void setUp() {
        registrationRepository = mock(ReturnRegistrationRepository.class);
        RegistrationWalRepository walRepository = mock(RegistrationWalRepository.class);
        registrationWriter = mock(RegistrationWriter.class);
        windowPolicy = mock(RegistrationWindowPolicy.class);
        cancelAttemptGuard = mock(CancelAttemptGuard.class);

        when(windowPolicy.targetDate()).thenReturn(TestFixtures.DATE);
        when(cancelAttemptGuard.personKey(anyString(), anyString(), anyString())).thenReturn(PERSON_KEY);
        when(cancelAttemptGuard.allowAttempt(any(), anyString())).thenReturn(true);
        when(cancelAttemptGuard.maxAttempts()).thenReturn(10);
        when(registrationWriter.cancel(any(), any())).thenReturn(true);

        service = new RegistrationService(registrationRepository, walRepository, registrationWriter,
                windowPolicy, TestFixtures.cancelPasswordHasher(), cancelAttemptGuard,
                TestFixtures.imlateProperties(), TestFixtures.clockAt(LocalTime.of(20, 15, 0)));
    }

    private void existing(ReturnRegistration row) {
        when(registrationRepository.findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
                any(), anyString(), anyString(), anyString())).thenReturn(Optional.ofNullable(row));
    }

    private static CancelCommand command(String password) {
        return new CancelCommand("1", "홍길동", "302", password, "1.2.3.4");
    }

    @Test
    @DisplayName("반·이름·호수와 비밀번호가 모두 맞으면 취소된다")
    void 모두_맞으면_취소된다() {
        ReturnRegistration row = TestFixtures.registration(7L, "1", "홍길동", "302");
        existing(row);

        CancelResult result = service.cancel(command(TestFixtures.CANCEL_PASSWORD));

        assertThat(result.date()).isEqualTo(TestFixtures.DATE);
        assertThat(result.alreadyCancelled()).isFalse();
        verify(registrationWriter).cancel(eq(7L), any());
        // 성공했으니 실패 누적은 지운다(다음에 또 취소할 때 상한이 남아 있으면 안 된다).
        verify(cancelAttemptGuard).reset(TestFixtures.DATE, PERSON_KEY);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 취소되지 않고 실패로 집계된다")
    void 비밀번호가_틀리면_취소되지_않는다() {
        existing(TestFixtures.registration(7L, "1", "홍길동", "302"));

        assertThatThrownBy(() -> service.cancel(command("9999")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CANCEL_REJECTED);

        verify(registrationWriter, never()).cancel(any(), any());
        verify(cancelAttemptGuard).recordFailure(eq(TestFixtures.DATE), eq(PERSON_KEY), any());
        verify(cancelAttemptGuard, never()).reset(any(), anyString());
    }

    @Test
    @DisplayName("등록이 없을 때와 비밀번호가 틀렸을 때의 응답이 완전히 같다(등록 여부가 새지 않는다)")
    void 실패_사유를_구분해_알려주지_않는다() {
        // (1) 그런 등록이 아예 없는 경우
        existing(null);
        ApiException noSuchRegistration = catchApiException(() -> service.cancel(command("1234")));

        // (2) 등록은 있는데 비밀번호가 틀린 경우
        existing(TestFixtures.registration(7L, "1", "홍길동", "302"));
        ApiException wrongPassword = catchApiException(() -> service.cancel(command("9999")));

        // 코드도 문구도 같아야 한다. 하나라도 다르면 "오늘 그 사람이 등록했는지"를 밖에서 알아낼 수 있다.
        assertThat(noSuchRegistration.code()).isEqualTo(wrongPassword.code());
        assertThat(noSuchRegistration.getMessage()).isEqualTo(wrongPassword.getMessage());
        // 존재하지 않는 등록에 대한 시도도 횟수에 포함된다(안 세면 그 경로로 무한 탐색이 가능하다).
        verify(cancelAttemptGuard, times(2)).recordFailure(eq(TestFixtures.DATE), eq(PERSON_KEY), any());
    }

    @Test
    @DisplayName("비밀번호가 없는 행(V2 이전 등록·WAL 복구분)은 취소할 수 없고, 실패 응답도 동일하다")
    void 비밀번호가_없는_행은_취소할_수_없다() {
        // 해시가 null 인 행 — 마이그레이션 이전에 등록됐거나 WAL 대사로 복구된 경우다.
        ReturnRegistration noPassword = ReturnRegistration.create(TestFixtures.DATE, "1", "홍길동", "302",
                "wal-legacy", TestFixtures.DATE.atTime(20, 0), TestFixtures.DATE.atTime(20, 0), null);
        existing(noPassword);

        ApiException thrown = catchApiException(() -> service.cancel(command("1234")));

        assertThat(thrown.code()).isEqualTo(ErrorCode.CANCEL_REJECTED);
        verify(registrationWriter, never()).cancel(any(), any());
    }

    @Test
    @DisplayName("시도 횟수를 다 쓰면 비밀번호를 확인하기도 전에 거절한다")
    void 시도_초과면_비밀번호를_보기_전에_막는다() {
        when(cancelAttemptGuard.allowAttempt(any(), anyString())).thenReturn(false);
        existing(TestFixtures.registration(7L, "1", "홍길동", "302"));

        // 비밀번호가 맞더라도 막힌다.
        assertThatThrownBy(() -> service.cancel(command(TestFixtures.CANCEL_PASSWORD)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CANCEL_LOCKED);

        // DB 를 아예 읽지 않는다 — 상한을 넘긴 뒤에는 조회조차 시켜 주지 않는다.
        verify(registrationRepository, never())
                .findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(any(), anyString(), anyString(), anyString());
        verify(registrationWriter, never()).cancel(any(), any());
    }

    @Test
    @DisplayName("이미 취소된 등록에 같은 비밀번호로 다시 요청하면 멱등하게 성공한다")
    void 이미_취소된_등록은_멱등하다() {
        ReturnRegistration cancelled = TestFixtures.cancelledRegistration(7L, "1", "홍길동", "302");
        existing(cancelled);
        // 이미 취소 상태이므로 writer 는 "바뀐 것 없음"을 보고한다.
        when(registrationWriter.cancel(any(), any())).thenReturn(false);

        CancelResult result = service.cancel(command(TestFixtures.CANCEL_PASSWORD));

        assertThat(result.alreadyCancelled()).isTrue();
        // 최초 취소 시각을 보존한다(언제 취소했는지가 근거로 남아야 한다).
        assertThat(result.cancelledAt()).isEqualTo(cancelled.getCancelledAt());
    }

    @Test
    @DisplayName("마감(21:45) 이후에는 취소할 수 없다 — 명단은 이미 사감에게 나갔다")
    void 마감_후에는_취소할_수_없다() {
        // 등록 창 정책이 던지는 예외가 그대로 전파되어야 한다.
        org.mockito.Mockito.doThrow(ApiException.of(ErrorCode.REGISTRATION_CLOSED, "마감되었습니다."))
                .when(windowPolicy).requireOpen();

        assertThatThrownBy(() -> service.cancel(command(TestFixtures.CANCEL_PASSWORD)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.REGISTRATION_CLOSED);

        // 마감이 우선이므로 시도 횟수도 소모하지 않는다.
        verify(cancelAttemptGuard, never()).recordFailure(any(), anyString(), any());
    }

    @Test
    @DisplayName("비밀번호 형식이 틀리면 시도 횟수를 소모하지 않는다(대입에 쓸 수 없는 요청이다)")
    void 형식_오류는_시도_횟수를_쓰지_않는다() {
        assertThatThrownBy(() -> service.cancel(command("12")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.cancel(command("abcd")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verify(cancelAttemptGuard, never()).recordFailure(any(), anyString(), any());
    }

    @Test
    @DisplayName("취소한 사람이 다시 등록하면 새 등록으로 되살아난다(유니크 제약에 막히지 않는다)")
    void 취소_후_재등록은_되살리기로_처리된다() {
        ReturnRegistration cancelled = TestFixtures.cancelledRegistration(7L, "1", "홍길동", "302");
        existing(cancelled);
        when(registrationWriter.reactivate(any(), anyString(), any()))
                .thenAnswer(invocation -> TestFixtures.registration(7L, "1", "홍길동", "302"));

        RegistrationResult result = service.register(new RegistrationCommand(
                "1", "홍길동", "302", "5678", "1.2.3.4"));

        // duplicate=false → 컨트롤러가 201 을 준다. 사용자 입장에서 방금 한 것은 "등록"이다.
        assertThat(result.duplicate()).isFalse();
        verify(registrationWriter).reactivate(eq(7L), anyString(), any());
        // 새 행을 만들지 않는다(유니크 제약 때문에 만들 수도 없다).
        verify(registrationWriter, never()).insert(any());
        // 되살리기는 통계를 다시 올리지 않는다 — 그 판단은 writer 안에 있으므로 여기서는 호출만 확인한다.
        verify(registrationWriter, never()).recover(any(), anyBoolean());
    }

    @Test
    @DisplayName("되살릴 때 비밀번호는 이번에 입력한 값으로 교체된다")
    void 되살릴_때_비밀번호가_교체된다() {
        ReturnRegistration cancelled = TestFixtures.cancelledRegistration(7L, "1", "홍길동", "302");
        existing(cancelled);
        when(registrationWriter.reactivate(any(), anyString(), any()))
                .thenAnswer(invocation -> TestFixtures.registration(7L, "1", "홍길동", "302"));

        service.register(new RegistrationCommand("1", "홍길동", "302", "5678", "1.2.3.4"));

        org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(registrationWriter).reactivate(eq(7L), hash.capture(), any());
        // 새 비밀번호로 검증되고, 예전 비밀번호로는 검증되지 않아야 한다.
        assertThat(TestFixtures.cancelPasswordHasher().matches("5678", hash.getValue())).isTrue();
        assertThat(TestFixtures.cancelPasswordHasher()
                .matches(TestFixtures.CANCEL_PASSWORD, hash.getValue())).isFalse();
    }

    private static ApiException catchApiException(Runnable action) {
        try {
            action.run();
        } catch (ApiException ex) {
            return ex;
        }
        throw new AssertionError("ApiException 이 발생하지 않았습니다.");
    }
}
