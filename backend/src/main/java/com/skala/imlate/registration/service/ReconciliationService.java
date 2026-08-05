package com.skala.imlate.registration.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.imlate.registration.domain.ReturnRegistration;
import com.skala.imlate.registration.domain.ReturnRegistrationRepository;
import com.skala.imlate.registration.wal.RegistrationWalRepository;
import com.skala.imlate.registration.wal.WalEntry;
import com.skala.imlate.registration.wal.WalStatus;

/**
 * WAL ↔ DB 대사 서비스(R8, SPEC §5.4).
 *
 * <p>22:00 마감 후 사감 발송(22:10) 직전에 실행되어, Redis WAL 에는 있는데 DB 에는 없는 등록을 찾아
 * DB 로 복구한다. 조회 페이지는 복구 없이 {@link #inspect(LocalDate)} 만 호출한다.
 *
 * <p>WAL 항목의 {@code status}(PENDING/FAILED) 는 판정에 쓰지 않는다. <b>실제 DB 존재 여부</b>로만 판단한다.
 *
 * <p><b>Redis 장애 시에도 예외를 던지지 않는다.</b> {@code status = WAL_UNAVAILABLE} 로 보고하고
 * 발송/조회는 DB 기준으로 계속 진행된다.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReturnRegistrationRepository registrationRepository;
    private final RegistrationWalRepository walRepository;
    private final RegistrationWriter registrationWriter;
    private final Clock clock;

    /**
     * @param registrationRepository 등록 저장소
     * @param walRepository          Redis WAL 저장소
     * @param registrationWriter     복구 INSERT 전용 트랜잭션 경계(REQUIRES_NEW)
     * @param clock                  서비스 기준 시계
     */
    public ReconciliationService(ReturnRegistrationRepository registrationRepository,
                                 RegistrationWalRepository walRepository,
                                 RegistrationWriter registrationWriter,
                                 Clock clock) {
        this.registrationRepository = registrationRepository;
        this.walRepository = walRepository;
        this.registrationWriter = registrationWriter;
        this.clock = clock;
    }

    /**
     * WAL ↔ DB 를 비교하고, WAL 에만 있는 항목을 DB 로 복구한다. (notification 모듈이 호출한다)
     *
     * @param date 대상일
     * @return 대사 결과
     */
    @Transactional
    public ReconciliationReport reconcile(LocalDate date) {
        return run(date, true);
    }

    /**
     * 복구 없이 비교만 한다(조회 페이지용).
     *
     * @param date 대상일
     * @return 대사 결과
     */
    @Transactional(readOnly = true)
    public ReconciliationReport inspect(LocalDate date) {
        return run(date, false);
    }

    private ReconciliationReport run(LocalDate date, boolean recover) {
        OffsetDateTime checkedAt = OffsetDateTime.now(clock);

        List<ReturnRegistration> dbRows =
                registrationRepository.findByRegistrationDateOrderByClassNameAscStudentNameAsc(date);
        long dbCount = dbRows.size();

        // --- WAL 읽기: Redis 장애는 예외 대신 WAL_UNAVAILABLE 로 보고한다 ---
        List<WalEntry> walEntries;
        if (!walRepository.isAvailable()) {
            log.warn("Reconciliation skipped — Redis(WAL) unavailable date={}", date);
            return unavailable(date, dbCount, checkedAt);
        }
        try {
            walEntries = walRepository.findAllByDate(date);
        } catch (RuntimeException ex) {
            log.warn("Reconciliation skipped — WAL read failed date={} cause={}", date, ex.toString());
            return unavailable(date, dbCount, checkedAt);
        }

        // --- personKey 기준으로 정렬/중복 제거 ---
        Map<String, WalEntry> walByPerson = new LinkedHashMap<>();
        for (WalEntry entry : walEntries) {
            walByPerson.putIfAbsent(entry.personKey(), entry);
        }
        Set<String> dbKeys = new LinkedHashSet<>();
        for (ReturnRegistration row : dbRows) {
            dbKeys.add(row.personKey());
        }
        long walCount = walByPerson.size();

        // --- WAL 에만 있는 항목(= DB 누락 후보) ---
        List<WalEntry> walOnlyEntries = new ArrayList<>();
        for (Map.Entry<String, WalEntry> entry : walByPerson.entrySet()) {
            if (!dbKeys.contains(entry.getKey())) {
                walOnlyEntries.add(entry.getValue());
            }
        }

        long recoveredCount = 0L;
        List<String> remainingWalOnly = new ArrayList<>();
        for (WalEntry entry : walOnlyEntries) {
            if (!recover) {
                remainingWalOnly.add(display(entry.className(), entry.studentName(), entry.roomNumber()));
                continue;
            }
            if (recoverOne(entry)) {
                recoveredCount++;
                dbCount++;
            } else {
                remainingWalOnly.add(display(entry.className(), entry.studentName(), entry.roomNumber()));
            }
        }

        // --- DB 에만 있는 항목(WAL TTL 만료 / 등록 당시 Redis 장애) ---
        List<String> dbOnly = new ArrayList<>();
        for (ReturnRegistration row : dbRows) {
            if (!walByPerson.containsKey(row.personKey())) {
                dbOnly.add(display(row.getClassName(), row.getStudentName(), row.getRoomNumber()));
            }
        }

        String status = decideStatus(remainingWalOnly, dbOnly, recoveredCount);
        ReconciliationReport report = new ReconciliationReport(date, status, dbCount, walCount, recoveredCount,
                remainingWalOnly, dbOnly, checkedAt);

        if (ReconciliationReport.STATUS_MISMATCH.equals(status)) {
            log.warn("Reconciliation mismatch date={} db={} wal={} recovered={} walOnly={} dbOnly={}",
                    date, dbCount, walCount, recoveredCount, remainingWalOnly.size(), dbOnly.size());
        } else {
            log.info("Reconciliation {} date={} db={} wal={} recovered={}", status, date, dbCount, walCount, recoveredCount);
        }
        return report;
    }

    /**
     * WAL 항목 1건을 DB 로 복구한다.
     *
     * <p>트랜잭션 경계: 복구 INSERT 는
     * {@link RegistrationWriter#recover(ReturnRegistration, boolean)} 의 REQUIRES_NEW 트랜잭션에서 수행한다.
     * 유니크 충돌이 나도 바깥 대사 트랜잭션이 rollback-only 로 오염되지 않는다.
     *
     * <p>통계 중복 집계 방지: WAL 상태가 {@code COMMITTED} 였다면 최초 등록 때 DB INSERT 가 성공했고
     * 등록 통계도 이미 올라갔다는 뜻이므로(그 뒤 행이 유실된 것), 복구 시 다시 집계하지 않는다.
     * {@code PENDING}/{@code FAILED} 는 최초 INSERT 자체가 실패해 집계되지 않았으므로 이때만 집계한다.
     *
     * @return 복구했으면 true, 이미 있거나 실패했으면 false
     */
    private boolean recoverOne(WalEntry entry) {
        // 중복 방지: 사람 기준·walId 기준 모두 확인한다.
        boolean alreadyExists = registrationRepository
                .findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
                        entry.registrationDate(), entry.className(), entry.studentName(), entry.roomNumber())
                .isPresent();
        if (alreadyExists || registrationRepository.existsByWalId(entry.walId())) {
            return false;
        }

        LocalDateTime registeredAt = entry.registeredAt() == null ? LocalDateTime.now(clock) : entry.registeredAt();
        // COMMITTED 였던 항목은 최초 등록 시점에 이미 통계에 반영되었으므로 재집계하지 않는다.
        boolean countAsNewRegistration = entry.status() != WalStatus.COMMITTED;
        try {
            ReturnRegistration recovered = registrationWriter.recover(ReturnRegistration.create(
                    entry.registrationDate(), entry.className(), entry.studentName(), entry.roomNumber(),
                    entry.walId(), registeredAt, LocalDateTime.now(clock)), countAsNewRegistration);
            walRepository.updateStatus(entry.walId(), entry.registrationDate(), WalStatus.COMMITTED);
            log.warn("Recovered missing registration from WAL id={} date={} walId={}",
                    recovered.getId(), entry.registrationDate(), entry.walId());
            return true;
        } catch (DataIntegrityViolationException ex) {
            // 동시에 다른 경로로 들어왔다면 이미 DB 에 있는 것이므로 문제가 아니다.
            log.info("Recovery skipped — already present date={} walId={}", entry.registrationDate(), entry.walId());
            return false;
        } catch (RuntimeException ex) {
            log.error("Recovery failed date={} walId={}", entry.registrationDate(), entry.walId(), ex);
            return false;
        }
    }

    private static String decideStatus(List<String> walOnly, List<String> dbOnly, long recoveredCount) {
        if (!walOnly.isEmpty() || !dbOnly.isEmpty()) {
            return ReconciliationReport.STATUS_MISMATCH;
        }
        return recoveredCount > 0 ? ReconciliationReport.STATUS_RECOVERED : ReconciliationReport.STATUS_CONSISTENT;
    }

    private static ReconciliationReport unavailable(LocalDate date, long dbCount, OffsetDateTime checkedAt) {
        return new ReconciliationReport(date, ReconciliationReport.STATUS_WAL_UNAVAILABLE,
                dbCount, 0L, 0L, List.of(), List.of(), checkedAt);
    }

    /** 보고용 표시 문자열({@code "1반/홍길동/302"}). */
    private static String display(String className, String studentName, String roomNumber) {
        return className + "/" + studentName + "/" + roomNumber;
    }
}
