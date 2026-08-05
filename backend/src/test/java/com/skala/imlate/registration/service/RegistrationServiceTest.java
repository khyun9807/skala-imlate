package com.skala.imlate.registration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

import com.skala.imlate.common.error.ApiException;
import com.skala.imlate.common.error.ErrorCode;
import com.skala.imlate.registration.domain.ReturnRegistration;
import com.skala.imlate.registration.domain.ReturnRegistrationRepository;
import com.skala.imlate.registration.wal.RegistrationWalRepository;
import com.skala.imlate.registration.wal.WalEntry;
import com.skala.imlate.registration.wal.WalStatus;
import com.skala.imlate.support.TestFixtures;

/**
 * {@link RegistrationService} 단위 테스트.
 *
 * <p>핵심 요구(R7)는 <b>Redis WAL 선행 기록(PENDING) → 중복 조회(DB READ) → DB INSERT → WAL COMMITTED</b>
 * 순서이며, 다음 두 가지가 이 테스트의 중심이다.
 * <ul>
 *   <li>Redis 장애가 등록 실패로 이어져서는 안 된다(가용성 우선).</li>
 *   <li>MySQL 이 완전히 죽어 중복 조회부터 실패하더라도 등록 의도는 WAL 에 <b>PENDING</b> 으로 남아야 한다.
 *       그래야 22:10 대사에서 명단이 복구된다(명단 누락 = 교육생이 밖에서 밤을 샌다).</li>
 * </ul>
 * 단, 검증을 통과하지 못한 입력은 WAL 을 오염시키면 안 되므로 WAL 기록보다 검증이 앞선다.
 */
@DisplayName("등록 서비스(RegistrationService)")
class RegistrationServiceTest {

    private ReturnRegistrationRepository registrationRepository;
    private RegistrationWalRepository walRepository;
    private RegistrationWriter registrationWriter;
    private RegistrationWindowPolicy windowPolicy;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        registrationRepository = mock(ReturnRegistrationRepository.class);
        walRepository = mock(RegistrationWalRepository.class);
        registrationWriter = mock(RegistrationWriter.class);
        windowPolicy = mock(RegistrationWindowPolicy.class);

        when(windowPolicy.targetDate()).thenReturn(TestFixtures.DATE);
        // 저장된 엔티티를 그대로 돌려준다(PK 는 이 테스트의 관심사가 아니다).
        when(registrationWriter.insert(any(ReturnRegistration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = new RegistrationService(registrationRepository, walRepository, registrationWriter,
                windowPolicy, TestFixtures.imlateProperties(), TestFixtures.clockAt(LocalTime.of(21, 3, 11)));
    }

    private void existing(Optional<ReturnRegistration> result) {
        when(registrationRepository.findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
                any(), anyString(), anyString(), anyString())).thenReturn(result);
    }

    private static RegistrationCommand command() {
        return new RegistrationCommand("1반", "홍길동", "302", "1.2.3.4");
    }

    @Test
    @DisplayName("신규 등록은 WAL append(PENDING) → 중복 조회 → DB INSERT → WAL COMMITTED 순서로 처리된다")
    void 신규_등록은_WAL_먼저_DB_나중_순서로_기록된다() {
        existing(Optional.empty());

        RegistrationResult result = service.register(command());

        assertThat(result.duplicate()).isFalse();
        assertThat(result.registration().getClassName()).isEqualTo("1반");
        assertThat(result.registration().getStudentName()).isEqualTo("홍길동");
        assertThat(result.registration().getRoomNumber()).isEqualTo("302");
        assertThat(result.registration().getRegistrationDate()).isEqualTo(TestFixtures.DATE);

        // WAL 이 DB 를 건드리는 첫 호출(중복 조회)보다 앞서야 한다 — DB 완전 장애 시 복구의 근거가 된다.
        ArgumentCaptor<WalEntry> walCaptor = ArgumentCaptor.forClass(WalEntry.class);
        InOrder order = inOrder(walRepository, registrationRepository, registrationWriter);
        order.verify(walRepository).append(walCaptor.capture());
        order.verify(registrationRepository).findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
                eq(TestFixtures.DATE), eq("1반"), eq("홍길동"), eq("302"));
        order.verify(registrationWriter).insert(any(ReturnRegistration.class));
        order.verify(walRepository).updateStatus(anyString(), eq(TestFixtures.DATE), eq(WalStatus.COMMITTED));
        order.verifyNoMoreInteractions();

        WalEntry appended = walCaptor.getValue();
        assertThat(appended.status()).isEqualTo(WalStatus.PENDING);
        assertThat(appended.walId()).isNotBlank();
        assertThat(appended.registrationDate()).isEqualTo(TestFixtures.DATE);
        assertThat(appended.clientIp()).isEqualTo("1.2.3.4");
        // WAL 항목과 DB 레코드는 같은 walId 로 1:1 대응해야 한다.
        assertThat(result.registration().getWalId()).isEqualTo(appended.walId());
        assertThat(result.registration().personKey()).isEqualTo(appended.personKey());
    }

    @Test
    @DisplayName("이미 등록된 사람이면 DB 저장 없이 duplicate=true 를 돌려주고 WAL 은 COMMITTED 로 정리한다")
    void 중복_등록은_기존_레코드를_그대로_돌려준다() {
        ReturnRegistration already = TestFixtures.registration(7L, "1반", "홍길동", "302");
        existing(Optional.of(already));

        RegistrationResult result = service.register(command());

        assertThat(result.duplicate()).isTrue();
        assertThat(result.registration()).isSameAs(already);
        verify(registrationWriter, never()).insert(any(ReturnRegistration.class));

        // WAL 은 중복 조회보다 먼저 PENDING 으로 남는다. 이미 DB 에 그 사람이 있으므로
        // 의도가 충족된 것으로 보고 COMMITTED 로 표시한다(삭제하지 않는다).
        ArgumentCaptor<WalEntry> walCaptor = ArgumentCaptor.forClass(WalEntry.class);
        verify(walRepository).append(walCaptor.capture());
        assertThat(walCaptor.getValue().status()).isEqualTo(WalStatus.PENDING);
        verify(walRepository).updateStatus(
                eq(walCaptor.getValue().walId()), eq(TestFixtures.DATE), eq(WalStatus.COMMITTED));
    }

    @Test
    @DisplayName("중복 조회 중 DB 장애가 나면 WAL 을 PENDING 그대로 둔 채 예외를 전파한다")
    void DB_장애로_중복조회가_실패하면_WAL_은_PENDING_으로_남는다() {
        when(registrationRepository.findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
                any(), anyString(), anyString(), anyString()))
                .thenThrow(new QueryTimeoutException("mysql is down"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> service.register(command()));

        // 등록 의도는 이미 WAL 에 남아 있어야 한다 — 이것이 22:10 대사 복구의 유일한 근거다.
        ArgumentCaptor<WalEntry> walCaptor = ArgumentCaptor.forClass(WalEntry.class);
        verify(walRepository).append(walCaptor.capture());
        assertThat(walCaptor.getValue().status()).isEqualTo(WalStatus.PENDING);
        assertThat(walCaptor.getValue().studentName()).isEqualTo("홍길동");

        // FAILED 로 바꾸면 통계 재집계 판정이 달라지므로 상태를 건드리면 안 된다(PENDING 유지).
        verify(walRepository, never()).updateStatus(anyString(), any(), any());
        verify(registrationWriter, never()).insert(any(ReturnRegistration.class));
    }

    @Test
    @DisplayName("Redis(WAL) 장애가 나도 등록은 성공한다(가용성 우선)")
    void Redis_장애에도_등록은_성공한다() {
        existing(Optional.empty());
        doThrow(new RuntimeException("redis down")).when(walRepository).append(any(WalEntry.class));

        RegistrationResult result = service.register(command());

        assertThat(result.duplicate()).isFalse();
        assertThat(result.registration().getStudentName()).isEqualTo("홍길동");
        verify(registrationWriter).insert(any(ReturnRegistration.class));
        // WAL 에 남기지 못했으므로 상태 갱신도 시도하지 않는다.
        verify(walRepository, never()).updateStatus(anyString(), any(), any());
    }

    @Test
    @DisplayName("동시 요청으로 유니크 충돌이 나면 재조회해 duplicate=true 로 멱등 응답한다")
    void 유니크_충돌은_멱등_응답으로_처리된다() {
        ReturnRegistration raced = TestFixtures.registration(9L, "1반", "홍길동", "302");
        when(registrationRepository.findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
                any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(raced));
        when(registrationWriter.insert(any(ReturnRegistration.class)))
                .thenThrow(new DataIntegrityViolationException("uk_return_registration_person"));

        RegistrationResult result = service.register(command());

        assertThat(result.duplicate()).isTrue();
        assertThat(result.registration()).isSameAs(raced);
        verify(walRepository).updateStatus(anyString(), eq(TestFixtures.DATE), eq(WalStatus.COMMITTED));
    }

    @Test
    @DisplayName("DB INSERT 가 실패하면 WAL 을 FAILED 로 표시하고 예외를 전파한다")
    void DB_저장_실패시_WAL_은_FAILED_가_된다() {
        existing(Optional.empty());
        when(registrationWriter.insert(any(ReturnRegistration.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.register(command()));

        verify(walRepository).updateStatus(anyString(), eq(TestFixtures.DATE), eq(WalStatus.FAILED));
    }

    @Test
    @DisplayName("등록 창이 닫혀 있으면 저장소를 건드리지 않고 즉시 거부한다")
    void 마감_후에는_아무것도_기록하지_않는다() {
        doThrow(ApiException.of(ErrorCode.REGISTRATION_CLOSED, "등록 마감 시간(22:00)이 지났습니다."))
                .when(windowPolicy).requireOpen();

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> service.register(command()))
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REGISTRATION_CLOSED));

        verifyNoInteractions(walRepository);
        verify(registrationWriter, never()).insert(any(ReturnRegistration.class));
    }

    @Test
    @DisplayName("입력은 앞뒤 공백 제거 + 연속 공백 축약으로 정규화된 뒤 저장된다")
    void 입력값을_정규화한다() {
        existing(Optional.empty());

        RegistrationResult result =
                service.register(new RegistrationCommand("  1반 ", "홍  길동", " 302  ", "1.2.3.4"));

        assertThat(result.registration().getClassName()).isEqualTo("1반");
        assertThat(result.registration().getStudentName()).isEqualTo("홍 길동");
        assertThat(result.registration().getRoomNumber()).isEqualTo("302");

        ArgumentCaptor<WalEntry> walCaptor = ArgumentCaptor.forClass(WalEntry.class);
        verify(walRepository).append(walCaptor.capture());
        assertThat(walCaptor.getValue().studentName()).isEqualTo("홍 길동");
    }

    @Test
    @DisplayName("허용되지 않은 문자·빈 값·길이 초과는 VALIDATION_FAILED 로 거부하고 WAL 도 남기지 않는다")
    void 잘못된_입력은_검증에서_거부된다() {
        assertValidationFailure(new RegistrationCommand("<script>", "홍길동", "302", "1.2.3.4"));
        assertValidationFailure(new RegistrationCommand("1반", "  ", "302", "1.2.3.4"));
        assertValidationFailure(new RegistrationCommand("1반", "홍길동", null, "1.2.3.4"));
        assertValidationFailure(new RegistrationCommand("1반", "가".repeat(21), "302", "1.2.3.4"));

        verifyNoInteractions(walRepository);
        verify(registrationWriter, never()).insert(any(ReturnRegistration.class));
    }

    @Test
    @DisplayName("커맨드가 null 이면 VALIDATION_FAILED 로 거부한다")
    void null_커맨드는_거부된다() {
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> service.register(null))
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("clientIp 가 비어 있으면 'unknown' 으로 기록한다")
    void 클라이언트_IP_가_없으면_unknown_으로_기록한다() {
        existing(Optional.empty());

        service.register(new RegistrationCommand("1반", "홍길동", "302", "  "));

        ArgumentCaptor<WalEntry> walCaptor = ArgumentCaptor.forClass(WalEntry.class);
        verify(walRepository).append(walCaptor.capture());
        assertThat(walCaptor.getValue().clientIp()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("normalize() 는 null 을 빈 문자열로, 연속 공백을 한 칸으로 바꾼다")
    void 정규화_유틸() {
        assertThat(RegistrationService.normalize(null)).isEmpty();
        assertThat(RegistrationService.normalize("  A  B  ")).isEqualTo("A B");
        assertThat(RegistrationService.normalize("1반")).isEqualTo("1반");
        assertThat(RegistrationService.normalize("A\t\tB")).isEqualTo("A B");
    }

    private void assertValidationFailure(RegistrationCommand command) {
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> service.register(command))
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
