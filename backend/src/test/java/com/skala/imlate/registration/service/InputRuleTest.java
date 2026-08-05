package com.skala.imlate.registration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 * 입력 규칙 단위 테스트 — <b>반·호수는 숫자만, 이름은 글자만</b>(운영 요청).
 *
 * <p>이 규칙은 등록과 취소 <u>양쪽에서 똑같이</u> 적용되어야 한다.
 * 한쪽만 좁으면 "등록은 됐는데 취소가 안 되는" 상태에 갇힌다 —
 * 취소 성립 조건이 "등록 때와 정확히 같은 값"이기 때문이다.
 */
@DisplayName("입력 규칙(반·호수 숫자 / 이름 글자)")
class InputRuleTest {

    private ReturnRegistrationRepository registrationRepository;
    private RegistrationWriter registrationWriter;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        registrationRepository = mock(ReturnRegistrationRepository.class);
        RegistrationWalRepository walRepository = mock(RegistrationWalRepository.class);
        registrationWriter = mock(RegistrationWriter.class);
        RegistrationWindowPolicy windowPolicy = mock(RegistrationWindowPolicy.class);
        CancelAttemptGuard guard = mock(CancelAttemptGuard.class);

        when(windowPolicy.targetDate()).thenReturn(TestFixtures.DATE);
        when(guard.personKey(anyString(), anyString(), anyString())).thenReturn("person");
        when(guard.allowAttempt(any(), anyString())).thenReturn(true);
        when(registrationWriter.insert(any(ReturnRegistration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(registrationRepository.findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
                any(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        service = new RegistrationService(registrationRepository, walRepository, registrationWriter,
                windowPolicy, TestFixtures.cancelPasswordHasher(), guard,
                TestFixtures.imlateProperties(), TestFixtures.clockAt(LocalTime.of(20, 0)));
    }

    private void register(String className, String studentName, String roomNumber) {
        service.register(new RegistrationCommand(className, studentName, roomNumber,
                TestFixtures.CANCEL_PASSWORD, "1.2.3.4"));
    }

    private ApiException registerExpectingFailure(String className, String studentName, String roomNumber) {
        try {
            register(className, studentName, roomNumber);
        } catch (ApiException ex) {
            return ex;
        }
        throw new AssertionError("검증에 걸리지 않았습니다: " + className + "/" + studentName + "/" + roomNumber);
    }

    @Test
    @DisplayName("반·호수가 숫자면 통과한다")
    void 숫자는_통과한다() {
        assertThatCode(() -> register("1", "홍길동", "302")).doesNotThrowAnyException();
        assertThatCode(() -> register("10", "김철수", "1204")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("반에 숫자가 아닌 글자가 섞이면 거부한다 — 예전 형식 \"1반\" 포함")
    void 반은_숫자만_받는다() {
        for (String bad : new String[] {"1반", "일", "A", "1-2", "1 2", "1층"}) {
            ApiException thrown = registerExpectingFailure(bad, "홍길동", "302");
            assertThat(thrown.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.getMessage()).contains("숫자");
        }
        verify(registrationWriter, never()).insert(any());
    }

    @Test
    @DisplayName("호수에 숫자가 아닌 글자가 섞이면 거부한다 — 예전 형식 \"B-101\" 포함")
    void 호수는_숫자만_받는다() {
        for (String bad : new String[] {"B-101", "302호", "3동 201", "302-1"}) {
            ApiException thrown = registerExpectingFailure("1", "홍길동", bad);
            assertThat(thrown.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.getMessage()).contains("숫자");
        }
        verify(registrationWriter, never()).insert(any());
    }

    @Test
    @DisplayName("이름에 숫자·기호가 섞이면 거부한다")
    void 이름은_글자만_받는다() {
        for (String bad : new String[] {"홍길동2", "302", "<script>", "홍-길동", "hong_gil"}) {
            ApiException thrown = registerExpectingFailure("1", bad, "302");
            assertThat(thrown.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.getMessage()).contains("한글");
        }
        verify(registrationWriter, never()).insert(any());
    }

    @Test
    @DisplayName("★ 이름 가운데 공백은 허용하지 않는다 — 붙여서 입력해야 한다")
    void 이름_가운데_공백은_거부한다() {
        for (String spaced : new String[] {"Alice Kim", "남궁 민수", "홍 길동", "홍  길동"}) {
            ApiException thrown = registerExpectingFailure("1", spaced, "302");
            assertThat(thrown.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            // 무엇이 잘못됐는지 알 수 있어야 한다. "한글·영문만" 이라고만 하면
            // 한글로 띄어 쓴 사람은 이유를 짐작할 수 없다.
            assertThat(thrown.getMessage()).contains("띄어쓰기");
        }
        verify(registrationWriter, never()).insert(any());

        // 붙여 쓰면 통과한다.
        assertThatCode(() -> register("1", "AliceKim", "302")).doesNotThrowAnyException();
        assertThatCode(() -> register("1", "남궁민수", "303")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("반·호수 가운데 공백도 허용하지 않는다")
    void 숫자_사이_공백도_거부한다() {
        assertThat(registerExpectingFailure("1 2", "홍길동", "302").code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(registerExpectingFailure("1", "홍길동", "30 2").code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("한글 낱자(ㄱ, ㅏ)만 있는 이름은 거부한다 — 조합이 덜 끝난 입력이다")
    void 낱자만_있는_이름은_거부한다() {
        assertThat(registerExpectingFailure("1", "ㅎㄱㄷ", "302").code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("앞뒤 공백은 여전히 지운다 — 막는 것은 가운데 공백뿐이다")
    void 앞뒤_공백은_지운다() {
        register("  1  ", "  홍길동  ", "  302  ");

        org.mockito.ArgumentCaptor<ReturnRegistration> saved =
                org.mockito.ArgumentCaptor.forClass(ReturnRegistration.class);
        verify(registrationWriter).insert(saved.capture());

        // 앞뒤 공백 때문에 거부되는 것이 아니라, 정리된 값으로 저장되어야 한다.
        assertThat(saved.getValue().getClassName()).isEqualTo("1");
        assertThat(saved.getValue().getStudentName()).isEqualTo("홍길동");
        assertThat(saved.getValue().getRoomNumber()).isEqualTo("302");
    }

    @Test
    @DisplayName("취소도 등록과 똑같은 규칙을 쓴다 — 한쪽만 좁으면 취소 불가 상태에 갇힌다")
    void 취소도_같은_규칙이다() {
        ApiException thrown = null;
        try {
            service.cancel(new CancelCommand("1반", "홍길동", "302", "1234", "1.2.3.4"));
        } catch (ApiException ex) {
            thrown = ex;
        }
        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.getMessage()).contains("숫자");

        assertThatThrownBy(() -> service.cancel(new CancelCommand("1", "홍길동2", "302", "1234", "1.2.3.4")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
