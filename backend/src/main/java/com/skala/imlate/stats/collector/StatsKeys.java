package com.skala.imlate.stats.collector;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 통계용 Redis 키 규약(SPEC §7.1).
 *
 * <p>일자별 키는 {@value #DAILY_TTL_DAYS} 일 TTL 을 갖고, 총계 키({@code *:total})와
 * 일자 목록 키({@code imlate:stats:days})는 TTL 을 두지 않는다.
 */
public final class StatsKeys {

    /** 모든 통계 키의 공통 접두어. */
    public static final String PREFIX = "imlate:stats:";

    /** 누적 페이지뷰(문자열 카운터). */
    public static final String PAGE_VIEWS_TOTAL = PREFIX + "pv:total";
    /** 누적 순 방문자(HyperLogLog). */
    public static final String UNIQUE_VISITORS_TOTAL = PREFIX + "uv:total";
    /**
     * 누적 등록 건수(문자열 카운터).
     *
     * <p>SPEC 확장 키. 등록 총계를 DB 전수 조회 없이 O(1) 로 얻기 위해 등록 이벤트마다 INCR 한다.
     */
    public static final String REGISTRATIONS_TOTAL = PREFIX + "reg:total";
    /** 통계가 기록된 일자 목록(SET, 값은 yyyy-MM-dd). */
    public static final String DAYS = PREFIX + "days";

    /** 일자별 키 보존 기간(일). */
    public static final int DAILY_TTL_DAYS = 400;
    /** 일자별 키 보존 기간(초). */
    public static final long DAILY_TTL_SECONDS = DAILY_TTL_DAYS * 24L * 60L * 60L;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private StatsKeys() {
        // 유틸 클래스
    }

    /** 키에 사용하는 일자 문자열(yyyy-MM-dd). */
    public static String day(LocalDate date) {
        return DAY_FORMAT.format(date);
    }

    /** {@code imlate:stats:pv:{date}} */
    public static String pageViews(LocalDate date) {
        return PREFIX + "pv:" + day(date);
    }

    /** {@code imlate:stats:uv:{date}} */
    public static String uniqueVisitors(LocalDate date) {
        return PREFIX + "uv:" + day(date);
    }

    /** {@code imlate:stats:reg:{date}} */
    public static String registrations(LocalDate date) {
        return PREFIX + "reg:" + day(date);
    }

    /**
     * {@link #DAYS} 집합의 원소를 일자로 되돌린다.
     *
     * @return 파싱 실패 시 {@code null} (통계는 서비스 장애가 되면 안 되므로 예외를 던지지 않는다)
     */
    public static LocalDate parseDay(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DAY_FORMAT);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
