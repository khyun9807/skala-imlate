package com.skala.imlate.support;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.test.util.ReflectionTestUtils;

import com.skala.imlate.common.properties.ImlateProperties;
import com.skala.imlate.registration.domain.ReturnRegistration;
import com.skala.imlate.registration.service.CancelPasswordHasher;
import com.skala.imlate.registration.service.ReconciliationReport;
import com.skala.imlate.registration.wal.WalEntry;
import com.skala.imlate.registration.wal.WalStatus;
import com.skala.imlate.stats.StatsSnapshot;

/**
 * 테스트 공용 픽스처. 고정 {@link Clock}, 설정 프로퍼티, 엔티티·DTO 생성을 한곳에 모은다.
 *
 * <p>모든 테스트는 인자 없는 {@code now()} 를 쓰지 않고 여기서 만든 고정 시계를 사용한다.
 */
public final class TestFixtures {

    /** 서비스 기준 시간대. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 테스트 기준일(2026-08-05, 수요일). */
    public static final LocalDate DATE = LocalDate.of(2026, 8, 5);

    /** 테스트용 조회 토큰 시크릿. */
    public static final String TOKEN_SECRET = "imlate-test-token-secret-0123456789";

    /** 테스트용 조회 페이지 기준 URL. */
    public static final String LOOKUP_BASE_URL = "https://imlate.example.com";

    /** 연장 복귀 시각(23:30). */
    public static final LocalTime RETURN_TIME = LocalTime.of(23, 30);

    /** 원래 통금 시각(22:30). */
    public static final LocalTime CURFEW_TIME = LocalTime.of(22, 30);

    /** 테스트용 취소 비밀번호(숫자 4자리). */
    public static final String CANCEL_PASSWORD = "1234";

    /** 실제 구현으로 만든 해시기. 상태가 없어 공유해도 안전하다. */
    private static final CancelPasswordHasher CANCEL_PASSWORD_HASHER =
            new CancelPasswordHasher(imlateProperties());

    /**
     * {@link #CANCEL_PASSWORD} 의 해시. <b>클래스 로딩 때 한 번만</b> 계산한다.
     *
     * <p>PBKDF2 는 일부러 느리게 만든 함수라 1회에 수십 ms 가 든다. 픽스처를 만들 때마다 새로 계산하면
     * 등록 엔티티를 수백 개 찍는 테스트에서 그것만으로 수십 초가 사라진다.
     * salt 가 고정되는 셈이지만 테스트 데이터이므로 문제되지 않는다.
     */
    private static final String CANCEL_PASSWORD_HASH = CANCEL_PASSWORD_HASHER.hash(CANCEL_PASSWORD);

    private TestFixtures() {
        // 유틸리티 클래스
    }

    /** 지정 날짜·시각(KST)에 고정된 시계. */
    public static Clock clockAt(LocalDate date, LocalTime time) {
        return Clock.fixed(ZonedDateTime.of(date, time, KST).toInstant(), KST);
    }

    /** {@link #DATE} 의 지정 시각(KST)에 고정된 시계. */
    public static Clock clockAt(LocalTime time) {
        return clockAt(DATE, time);
    }

    /** 기본 등록 창 {@code [00:00, 22:00)} 설정. */
    public static ImlateProperties imlateProperties() {
        return imlateProperties(LocalTime.MIDNIGHT, LocalTime.of(22, 0));
    }

    /** 등록 창 시각만 바꾼 설정. */
    public static ImlateProperties imlateProperties(LocalTime openTime, LocalTime closeTime) {
        return new ImlateProperties(
                "Asia/Seoul",
                new ImlateProperties.Registration(openTime, closeTime, RETURN_TIME, CURFEW_TIME, 20, 20),
                new ImlateProperties.Wal("imlate:test:wal", 7),
                new ImlateProperties.Lookup(LOOKUP_BASE_URL, TOKEN_SECRET, 48),
                new ImlateProperties.Admin("test-admin-key"));
    }

    /** 조회 토큰 시크릿만 바꾼 설정. */
    public static ImlateProperties imlatePropertiesWithSecret(String secret) {
        return new ImlateProperties(
                "Asia/Seoul",
                new ImlateProperties.Registration(LocalTime.MIDNIGHT, LocalTime.of(22, 0),
                        RETURN_TIME, CURFEW_TIME, 20, 20),
                new ImlateProperties.Wal("imlate:test:wal", 7),
                new ImlateProperties.Lookup(LOOKUP_BASE_URL, secret, 48),
                new ImlateProperties.Admin("test-admin-key"));
    }

    /** 등록 엔티티. {@code id} 가 있으면 리플렉션으로 PK 를 채운다(팩토리는 PK 를 받지 않는다). */
    public static ReturnRegistration registration(Long id, LocalDate date, String className,
                                                  String studentName, String roomNumber) {
        ReturnRegistration entity = ReturnRegistration.create(date, className, studentName, roomNumber,
                "wal-" + (id == null ? "new" : id),
                date.atTime(21, 3, 11), date.atTime(21, 3, 12),
                CANCEL_PASSWORD_HASH);
        if (id != null) {
            ReflectionTestUtils.setField(entity, "id", id);
        }
        return entity;
    }

    /**
     * 취소된 등록 엔티티.
     *
     * <p>대사가 취소분을 되살리지 않는지, 재등록이 되살리기로 처리되는지 확인할 때 쓴다.
     */
    public static ReturnRegistration cancelledRegistration(Long id, String className,
                                                           String studentName, String roomNumber) {
        ReturnRegistration entity = registration(id, className, studentName, roomNumber);
        entity.cancel(DATE.atTime(20, 0, 0));
        return entity;
    }

    /**
     * 테스트용 취소 비밀번호 해시기.
     *
     * <p>실제 구현을 그대로 쓴다 — 해시·검증은 등록과 취소가 반드시 같은 방식이어야 하고,
     * 가짜로 바꿔치면 그 대칭이 깨져도 테스트가 통과해 버린다.
     *
     * <p>인스턴스를 하나만 만들어 재사용한다. 생성 자체는 싸지만, 아래 {@link #CANCEL_PASSWORD_HASH}
     * 를 한 번만 계산하기 위한 근거지이기도 하다.
     */
    public static CancelPasswordHasher cancelPasswordHasher() {
        return CANCEL_PASSWORD_HASHER;
    }

    /** {@link #DATE} 기준 등록 엔티티. */
    public static ReturnRegistration registration(Long id, String className,
                                                  String studentName, String roomNumber) {
        return registration(id, DATE, className, studentName, roomNumber);
    }

    /** PENDING 상태 WAL 항목. */
    public static WalEntry walEntry(String walId, LocalDate date, String className,
                                    String studentName, String roomNumber) {
        return new WalEntry(walId, date, className, studentName, roomNumber,
                date.atTime(21, 3, 11), WalStatus.PENDING, "127.0.0.1");
    }

    /** 일치(CONSISTENT) 대사 결과. */
    public static ReconciliationReport consistentReport(LocalDate date, long count) {
        return new ReconciliationReport(date, ReconciliationReport.STATUS_CONSISTENT,
                count, count, 0L, List.of(), List.of(), checkedAt(date));
    }

    /** 대사 수행 시각(22:10 KST). */
    public static OffsetDateTime checkedAt(LocalDate date) {
        return ZonedDateTime.of(date, LocalTime.of(22, 10), KST).toOffsetDateTime();
    }

    /** 표시용 통계 스냅샷. */
    public static StatsSnapshot stats() {
        return new StatsSnapshot(540L, 88L, 2100L, 210L, 12L, 320L);
    }
}
