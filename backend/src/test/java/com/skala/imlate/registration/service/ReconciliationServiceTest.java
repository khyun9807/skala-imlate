package com.skala.imlate.registration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.skala.imlate.registration.domain.ReturnRegistration;
import com.skala.imlate.registration.domain.ReturnRegistrationRepository;
import com.skala.imlate.registration.wal.RegistrationWalRepository;
import com.skala.imlate.registration.wal.WalEntry;
import com.skala.imlate.registration.wal.WalStatus;
import com.skala.imlate.support.TestFixtures;

/**
 * {@link ReconciliationService} 단위 테스트(R8).
 *
 * <p>WAL 에만 있는 등록은 DB 로 복구되어야 하고, 완전 일치면 {@code CONSISTENT},
 * Redis 를 쓸 수 없으면 예외 없이 {@code WAL_UNAVAILABLE} 로 보고해야 한다.
 */
@DisplayName("WAL ↔ DB 대사 서비스(ReconciliationService)")
class ReconciliationServiceTest {

    private ReturnRegistrationRepository registrationRepository;
    private RegistrationWalRepository walRepository;
    private RegistrationWriter registrationWriter;
    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        registrationRepository = mock(ReturnRegistrationRepository.class);
        walRepository = mock(RegistrationWalRepository.class);
        registrationWriter = mock(RegistrationWriter.class);

        when(registrationWriter.recover(any(ReturnRegistration.class), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = new ReconciliationService(registrationRepository, walRepository, registrationWriter,
                TestFixtures.clockAt(LocalTime.of(22, 10)));
    }

    private void dbRows(List<ReturnRegistration> rows) {
        when(registrationRepository.findByRegistrationDateOrderByClassNameAscStudentNameAsc(TestFixtures.DATE))
                .thenReturn(rows);
    }

    private void walRows(List<WalEntry> entries) {
        when(walRepository.isAvailable()).thenReturn(true);
        when(walRepository.findAllByDate(TestFixtures.DATE)).thenReturn(entries);
    }

    @Test
    @DisplayName("취소된 등록은 WAL 에 남아 있어도 되살리지 않는다")
    void 취소된_등록은_되살리지_않는다() {
        // 취소는 소프트 삭제라 DB 행은 남지만 WAL 항목도 그대로 남아 있다.
        // 이 둘을 그냥 비교하면 "WAL 에만 있다"고 오판해 취소한 등록을 명단에 다시 올리게 된다.
        // 그러면 취소가 조용히 무효가 되고, 교육생은 자기가 뺐다고 믿는 이름이 사감 명단에 그대로 있게 된다.
        ReturnRegistration cancelled = TestFixtures.cancelledRegistration(7L, "1반", "홍길동", "302");
        dbRows(List.of(cancelled));
        walRows(List.of(TestFixtures.walEntry("wal-1", TestFixtures.DATE, "1반", "홍길동", "302")));

        ReconciliationReport report = service.reconcile(TestFixtures.DATE);

        // 복구가 일어나지 않아야 한다.
        verify(registrationWriter, never()).recover(any(), anyBoolean());
        assertThat(report.recoveredCount()).isZero();
        // 취소한 사람은 양쪽(DB·WAL) 어디에도 세지 않는다 — 한쪽에서만 빼면 가짜 불일치가 뜬다.
        assertThat(report.dbCount()).isZero();
        assertThat(report.walCount()).isZero();
        assertThat(report.status()).isEqualTo(ReconciliationReport.STATUS_CONSISTENT);
        assertThat(report.walOnly()).isEmpty();
        assertThat(report.dbOnly()).isEmpty();
    }

    @Test
    @DisplayName("취소한 사람이 섞여 있어도 나머지 인원의 대사는 정상 동작한다")
    void 취소분이_섞여도_나머지는_정상_대사한다() {
        ReturnRegistration active = TestFixtures.registration(1L, "1반", "김교육", "301");
        ReturnRegistration cancelled = TestFixtures.cancelledRegistration(2L, "1반", "홍길동", "302");
        dbRows(List.of(active, cancelled));
        walRows(List.of(
                TestFixtures.walEntry("wal-1", TestFixtures.DATE, "1반", "김교육", "301"),
                TestFixtures.walEntry("wal-2", TestFixtures.DATE, "1반", "홍길동", "302")));

        ReconciliationReport report = service.reconcile(TestFixtures.DATE);

        assertThat(report.dbCount()).isEqualTo(1L);
        assertThat(report.walCount()).isEqualTo(1L);
        assertThat(report.status()).isEqualTo(ReconciliationReport.STATUS_CONSISTENT);
        verify(registrationWriter, never()).recover(any(), anyBoolean());
    }

    @Test
    @DisplayName("WAL 에만 있는 등록은 DB 로 복구되고 상태는 RECOVERED 가 된다")
    void WAL_전용_항목을_복구한다() {
        WalEntry missing = TestFixtures.walEntry("wal-1", TestFixtures.DATE, "1반", "홍길동", "302");
        dbRows(List.of());
        walRows(List.of(missing));
        when(registrationRepository.findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
                any(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(registrationRepository.existsByWalId("wal-1")).thenReturn(false);

        ReconciliationReport report = service.reconcile(TestFixtures.DATE);

        assertThat(report.status()).isEqualTo(ReconciliationReport.STATUS_RECOVERED);
        assertThat(report.recoveredCount()).isEqualTo(1L);
        assertThat(report.dbCount()).isEqualTo(1L);
        assertThat(report.walCount()).isEqualTo(1L);
        assertThat(report.walOnly()).isEmpty();
        assertThat(report.dbOnly()).isEmpty();
        assertThat(report.checkedAt()).isNotNull();

        // PENDING 상태였던 WAL 항목이므로 통계에 새 등록으로 반영한다.
        verify(registrationWriter).recover(any(ReturnRegistration.class), eq(true));
        // 복구한 항목의 WAL 상태는 COMMITTED 로 정리한다.
        verify(walRepository).updateStatus(eq("wal-1"), eq(TestFixtures.DATE), eq(WalStatus.COMMITTED));
    }

    @Test
    @DisplayName("WAL 과 DB 가 일치하면 CONSISTENT 이고 복구를 시도하지 않는다")
    void 일치하면_CONSISTENT_다() {
        ReturnRegistration row = TestFixtures.registration(1L, "1반", "홍길동", "302");
        dbRows(List.of(row));
        walRows(List.of(TestFixtures.walEntry("wal-1", TestFixtures.DATE, "1반", "홍길동", "302")));

        ReconciliationReport report = service.reconcile(TestFixtures.DATE);

        assertThat(report.status()).isEqualTo(ReconciliationReport.STATUS_CONSISTENT);
        assertThat(report.dbCount()).isEqualTo(1L);
        assertThat(report.walCount()).isEqualTo(1L);
        assertThat(report.recoveredCount()).isZero();
        assertThat(report.walOnly()).isEmpty();
        assertThat(report.dbOnly()).isEmpty();
        assertThat(report.summaryText()).isEqualTo("DB 1 / WAL 1 (일치)");
        verify(registrationWriter, never()).recover(any(ReturnRegistration.class), anyBoolean());
    }

    @Test
    @DisplayName("Redis 를 쓸 수 없으면 예외 없이 WAL_UNAVAILABLE 로 보고한다")
    void WAL_사용_불가시_WAL_UNAVAILABLE_이다() {
        dbRows(List.of(TestFixtures.registration(1L, "1반", "홍길동", "302")));
        when(walRepository.isAvailable()).thenReturn(false);

        ReconciliationReport report = service.reconcile(TestFixtures.DATE);

        assertThat(report.status()).isEqualTo(ReconciliationReport.STATUS_WAL_UNAVAILABLE);
        assertThat(report.dbCount()).isEqualTo(1L);
        assertThat(report.walCount()).isZero();
        assertThat(report.recoveredCount()).isZero();
        assertThat(report.walOnly()).isEmpty();
        assertThat(report.dbOnly()).isEmpty();
        verify(walRepository, never()).findAllByDate(any());
        verify(registrationWriter, never()).recover(any(ReturnRegistration.class), anyBoolean());
    }

    @Test
    @DisplayName("WAL 읽기가 실패해도 예외를 던지지 않고 WAL_UNAVAILABLE 로 보고한다")
    void WAL_읽기_실패도_WAL_UNAVAILABLE_이다() {
        dbRows(List.of());
        when(walRepository.isAvailable()).thenReturn(true);
        when(walRepository.findAllByDate(TestFixtures.DATE))
                .thenThrow(new QueryTimeoutException("redis timeout"));

        ReconciliationReport report = service.reconcile(TestFixtures.DATE);

        assertThat(report.status()).isEqualTo(ReconciliationReport.STATUS_WAL_UNAVAILABLE);
        assertThat(report.summaryText()).contains("WAL 확인 불가");
    }

    @Test
    @DisplayName("inspect() 는 복구하지 않고 WAL 전용 항목을 그대로 보고한다(MISMATCH)")
    void inspect_는_복구하지_않는다() {
        dbRows(List.of());
        walRows(List.of(TestFixtures.walEntry("wal-1", TestFixtures.DATE, "1반", "홍길동", "302")));

        ReconciliationReport report = service.inspect(TestFixtures.DATE);

        assertThat(report.status()).isEqualTo(ReconciliationReport.STATUS_MISMATCH);
        assertThat(report.recoveredCount()).isZero();
        assertThat(report.dbCount()).isZero();
        assertThat(report.walOnly()).containsExactly("1반/홍길동/302");
        verify(registrationWriter, never()).recover(any(ReturnRegistration.class), anyBoolean());
    }

    @Test
    @DisplayName("DB 에만 있고 WAL 에 없는 항목이 남으면 MISMATCH 로 보고한다(WAL TTL 만료 등)")
    void DB_전용_항목이_있으면_MISMATCH_다() {
        dbRows(List.of(TestFixtures.registration(1L, "2반", "김철수", "410")));
        walRows(List.of());

        ReconciliationReport report = service.reconcile(TestFixtures.DATE);

        assertThat(report.status()).isEqualTo(ReconciliationReport.STATUS_MISMATCH);
        assertThat(report.dbOnly()).containsExactly("2반/김철수/410");
        assertThat(report.walOnly()).isEmpty();
    }

    @Test
    @DisplayName("이미 DB 에 있는 WAL 항목은 다시 저장하지 않는다(중복 복구 방지)")
    void 이미_존재하는_항목은_복구하지_않는다() {
        WalEntry entry = TestFixtures.walEntry("wal-9", TestFixtures.DATE, "1반", "홍길동", "302");
        // DB 목록 조회에는 잡히지 않지만(정렬 조회 시점 차이) 단건 조회로는 존재하는 상황
        dbRows(List.of());
        walRows(List.of(entry));
        when(registrationRepository.findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
                any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(TestFixtures.registration(3L, "1반", "홍길동", "302")));

        ReconciliationReport report = service.reconcile(TestFixtures.DATE);

        assertThat(report.recoveredCount()).isZero();
        assertThat(report.walOnly()).containsExactly("1반/홍길동/302");
        assertThat(report.status()).isEqualTo(ReconciliationReport.STATUS_MISMATCH);
        verify(registrationWriter, never()).recover(any(ReturnRegistration.class), anyBoolean());
    }

    @Test
    @DisplayName("PENDING/FAILED 상태의 WAL 항목도 DB 존재 여부만으로 판단한다")
    void 상태와_무관하게_DB_존재_여부로_판단한다() {
        WalEntry failed = new WalEntry("wal-f", TestFixtures.DATE, "1반", "홍길동", "302",
                TestFixtures.DATE.atTime(21, 0), WalStatus.FAILED, "1.2.3.4");
        dbRows(List.of(TestFixtures.registration(1L, "1반", "홍길동", "302")));
        walRows(List.of(failed));

        ReconciliationReport report = service.reconcile(TestFixtures.DATE);

        assertThat(report.status()).isEqualTo(ReconciliationReport.STATUS_CONSISTENT);
        verify(registrationWriter, never()).recover(any(ReturnRegistration.class), anyBoolean());
    }

    @Test
    @DisplayName("COMMITTED 였던 WAL 항목을 복구할 때는 등록 통계를 다시 올리지 않는다(중복 집계 방지)")
    void COMMITTED_복구는_통계를_재집계하지_않는다() {
        // 최초 등록 때 DB INSERT 가 성공(=통계 반영 완료)했지만 이후 행이 유실된 상황.
        WalEntry committed = new WalEntry("wal-c", TestFixtures.DATE, "1반", "홍길동", "302",
                TestFixtures.DATE.atTime(21, 0), WalStatus.COMMITTED, "1.2.3.4");
        dbRows(List.of());
        walRows(List.of(committed));
        when(registrationRepository.findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
                any(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(registrationRepository.existsByWalId("wal-c")).thenReturn(false);

        ReconciliationReport report = service.reconcile(TestFixtures.DATE);

        assertThat(report.status()).isEqualTo(ReconciliationReport.STATUS_RECOVERED);
        assertThat(report.recoveredCount()).isEqualTo(1L);
        // 행은 복구하되 RegistrationCreatedEvent 는 발행하지 않아야 한다.
        verify(registrationWriter).recover(any(ReturnRegistration.class), eq(false));
    }
}
