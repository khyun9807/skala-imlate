package com.skala.imlate.stats;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.skala.imlate.common.error.ApiException;
import com.skala.imlate.common.error.ErrorCode;
import com.skala.imlate.common.properties.StatsProperties;
import com.skala.imlate.stats.collector.StatsKeys;
import com.skala.imlate.stats.domain.DailyStat;
import com.skala.imlate.stats.domain.DailyStatRepository;

/**
 * 통계 조회 서비스(SPEC §7.2).
 *
 * <p>Redis 실시간 카운터를 우선 사용하고, Redis 장애 시에는 {@code daily_stat} 스냅샷으로 폴백한다.
 * 통계 때문에 화면이 깨지면 안 되므로 <b>어떤 경우에도 예외를 던지지 않고 0 을 반환</b>한다.
 * 다만 {@link #daily(LocalDate, LocalDate)} 의 잘못된 기간 입력은 사용자 실수이므로
 * {@link ErrorCode#VALIDATION_FAILED} 로 알려준다.
 */
@Service
public class StatsQueryService {

    private static final Logger log = LoggerFactory.getLogger(StatsQueryService.class);

    /** 일별 조회 최대 기간(일). */
    public static final int MAX_RANGE_DAYS = 366;
    /** {@code from} 이 없을 때 사용하는 기본 기간(일). */
    private static final int DEFAULT_RANGE_DAYS = 30;

    /** 일별 집계 배열 인덱스. */
    private static final int PV = 0;
    private static final int UV = 1;
    private static final int REG = 2;
    private static final int METRIC_COUNT = 3;

    private final StringRedisTemplate redisTemplate;
    private final DailyStatRepository dailyStatRepository;
    private final Clock clock;
    private final boolean enabled;

    public StatsQueryService(StringRedisTemplate redisTemplate,
                             DailyStatRepository dailyStatRepository,
                             StatsProperties statsProperties,
                             Clock clock) {
        this.redisTemplate = redisTemplate;
        this.dailyStatRepository = dailyStatRepository;
        this.clock = clock;
        this.enabled = statsProperties.enabled();
    }

    /**
     * 현재 통계 요약. Redis → DB 순으로 폴백하며 실패해도 예외 없이 0 을 채운다.
     */
    public StatsSnapshot snapshot() {
        LocalDate today = LocalDate.now(clock);
        long[] fromRedis = readRedisSnapshot(today);
        if (fromRedis == null) {
            return snapshotFromDatabase(today);
        }

        long totalPageViews = fromRedis[0];
        long todayPageViews = fromRedis[1];
        long totalVisitors = fromRedis[2];
        long todayVisitors = fromRedis[3];
        long todayRegistrations = fromRedis[4];
        long totalRegistrations = fromRedis[5];

        // 총계 키가 비어 있으면(Redis 초기화·신규 도입 등) DB 스냅샷 합계 + 오늘 값으로 보정한다.
        if (totalPageViews <= 0L) {
            totalPageViews = nullSafe(() -> dailyStatRepository.sumPageViewsBefore(today)) + todayPageViews;
        }
        if (totalVisitors <= 0L) {
            totalVisitors = nullSafe(() -> dailyStatRepository.sumUniqueVisitorsBefore(today)) + todayVisitors;
        }
        if (totalRegistrations <= 0L) {
            totalRegistrations = nullSafe(() -> dailyStatRepository.sumRegistrationsBefore(today)) + todayRegistrations;
        }

        return new StatsSnapshot(totalVisitors, todayVisitors,
                totalPageViews, todayPageViews,
                todayRegistrations, totalRegistrations);
    }

    /**
     * 일자별 통계 목록. 기간 내 모든 날짜를 오름차순으로 빠짐없이 반환한다(데이터 없는 날은 0).
     *
     * <p>DB 스냅샷을 기준으로 삼되, 아직 스냅샷이 만들어지지 않은 날짜(오늘/어제 등)는
     * Redis 실시간 값으로 보강한다.
     *
     * @param from 시작일(포함). {@code null} 이면 {@code to} 기준 최근 {@value #DEFAULT_RANGE_DAYS} 일
     * @param to   종료일(포함). {@code null} 이면 오늘
     * @throws ApiException 시작일이 종료일보다 늦거나 기간이 {@value #MAX_RANGE_DAYS} 일을 넘는 경우
     */
    public List<DailyStatView> daily(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate end = (to != null) ? to : today;
        LocalDate start = (from != null) ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);

        if (start.isAfter(end)) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "조회 시작일이 종료일보다 늦습니다.");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1L;
        if (days > MAX_RANGE_DAYS) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED,
                    "조회 기간은 최대 " + MAX_RANGE_DAYS + "일까지 가능합니다.");
        }

        // 기간 내 모든 날짜를 0 으로 초기화(일자 오름차순 유지).
        Map<LocalDate, long[]> merged = new LinkedHashMap<>();
        for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
            merged.put(cursor, new long[METRIC_COUNT]);
        }

        // 1) DB 스냅샷 우선 반영
        Set<LocalDate> withoutSnapshot = new HashSet<>(merged.keySet());
        try {
            for (DailyStat stat : dailyStatRepository.findByStatDateBetweenOrderByStatDateAsc(start, end)) {
                long[] metrics = merged.get(stat.getStatDate());
                if (metrics == null) {
                    continue;
                }
                metrics[PV] = stat.getPageViews();
                metrics[UV] = stat.getUniqueVisitors();
                metrics[REG] = stat.getRegistrations();
                withoutSnapshot.remove(stat.getStatDate());
            }
        } catch (Exception ex) {
            log.warn("일별 통계 DB 조회 실패 — Redis 값만 사용합니다: {}", ex.toString());
        }

        // 2) 스냅샷이 없는 날짜 + 오늘은 Redis 실시간 값으로 보강
        enrichFromRedis(merged, withoutSnapshot, today);

        List<DailyStatView> views = new ArrayList<>(merged.size());
        merged.forEach((date, metrics) ->
                views.add(new DailyStatView(date, metrics[UV], metrics[PV], metrics[REG])));
        return views;
    }

    // ------------------------------------------------------------------
    // Redis 조회
    // ------------------------------------------------------------------

    /**
     * 요약 통계를 파이프라인 1회로 읽는다.
     *
     * @return {@code [pvTotal, pvToday, uvTotal, uvToday, regToday, regTotal]}. 실패 시 {@code null}
     */
    private long[] readRedisSnapshot(LocalDate today) {
        if (!enabled) {
            return null;
        }
        final String pageViewsToday = StatsKeys.pageViews(today);
        final String visitorsToday = StatsKeys.uniqueVisitors(today);
        final String registrationsToday = StatsKeys.registrations(today);
        try {
            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection conn = (StringRedisConnection) connection;
                conn.get(StatsKeys.PAGE_VIEWS_TOTAL);
                conn.get(pageViewsToday);
                conn.pfCount(StatsKeys.UNIQUE_VISITORS_TOTAL);
                conn.pfCount(visitorsToday);
                conn.get(registrationsToday);
                conn.get(StatsKeys.REGISTRATIONS_TOTAL);
                return null;
            });
            return new long[] {
                    toLong(results, 0), toLong(results, 1), toLong(results, 2),
                    toLong(results, 3), toLong(results, 4), toLong(results, 5)
            };
        } catch (Exception ex) {
            log.warn("Redis 통계 조회 실패 — DB 스냅샷으로 대체합니다: {}", ex.toString());
            return null;
        }
    }

    /** 스냅샷이 없는 날짜와 오늘을 Redis 실시간 값으로 보강한다(값이 더 큰 쪽 채택). */
    private void enrichFromRedis(Map<LocalDate, long[]> merged, Set<LocalDate> withoutSnapshot, LocalDate today) {
        if (!enabled) {
            return;
        }
        try {
            Set<LocalDate> recordedDays = readRecordedDays();
            final List<LocalDate> targets = new ArrayList<>();
            for (LocalDate date : merged.keySet()) {
                // 오늘은 항상 실시간 값을 확인하고, 나머지는 "스냅샷 없음 + Redis 에 기록 있음" 인 날만 조회한다.
                if (date.equals(today) || (withoutSnapshot.contains(date) && recordedDays.contains(date))) {
                    targets.add(date);
                }
            }
            if (targets.isEmpty()) {
                return;
            }

            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection conn = (StringRedisConnection) connection;
                for (LocalDate date : targets) {
                    conn.get(StatsKeys.pageViews(date));
                    conn.pfCount(StatsKeys.uniqueVisitors(date));
                    conn.get(StatsKeys.registrations(date));
                }
                return null;
            });

            for (int i = 0; i < targets.size(); i++) {
                long[] metrics = merged.get(targets.get(i));
                if (metrics == null) {
                    continue;
                }
                int base = i * METRIC_COUNT;
                metrics[PV] = Math.max(metrics[PV], toLong(results, base));
                metrics[UV] = Math.max(metrics[UV], toLong(results, base + 1));
                metrics[REG] = Math.max(metrics[REG], toLong(results, base + 2));
            }
        } catch (Exception ex) {
            log.warn("Redis 일별 통계 보강 실패 — DB 값만 사용합니다: {}", ex.toString());
        }
    }

    /** {@code imlate:stats:days} 집합을 읽어 통계가 기록된 일자를 반환한다. */
    private Set<LocalDate> readRecordedDays() {
        Set<LocalDate> days = new HashSet<>();
        Set<String> raw = redisTemplate.opsForSet().members(StatsKeys.DAYS);
        if (raw == null) {
            return days;
        }
        for (String value : raw) {
            LocalDate parsed = StatsKeys.parseDay(value);
            if (parsed != null) {
                days.add(parsed);
            }
        }
        return days;
    }

    // ------------------------------------------------------------------
    // DB 폴백
    // ------------------------------------------------------------------

    /** Redis 를 쓸 수 없을 때 DB 스냅샷만으로 요약을 만든다. */
    private StatsSnapshot snapshotFromDatabase(LocalDate today) {
        try {
            long totalPageViews = nullSafe(() -> dailyStatRepository.sumPageViewsBefore(today));
            long totalVisitors = nullSafe(() -> dailyStatRepository.sumUniqueVisitorsBefore(today));
            long totalRegistrations = nullSafe(() -> dailyStatRepository.sumRegistrationsBefore(today));

            long todayPageViews = 0L;
            long todayVisitors = 0L;
            long todayRegistrations = 0L;
            DailyStat todayStat = dailyStatRepository.findById(today).orElse(null);
            if (todayStat != null) {
                todayPageViews = todayStat.getPageViews();
                todayVisitors = todayStat.getUniqueVisitors();
                todayRegistrations = todayStat.getRegistrations();
            }

            return new StatsSnapshot(totalVisitors + todayVisitors, todayVisitors,
                    totalPageViews + todayPageViews, todayPageViews,
                    todayRegistrations, totalRegistrations + todayRegistrations);
        } catch (Exception ex) {
            log.warn("DB 통계 폴백도 실패했습니다 — 0 으로 응답합니다: {}", ex.toString());
            return StatsSnapshot.empty();
        }
    }

    // ------------------------------------------------------------------
    // 변환 유틸
    // ------------------------------------------------------------------

    /** 파이프라인 결과의 {@code index} 번째 값을 long 으로 변환한다. 없거나 해석 불가면 0. */
    private static long toLong(List<Object> results, int index) {
        if (results == null || index < 0 || index >= results.size()) {
            return 0L;
        }
        Object value = results.get(index);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof byte[] bytes) {
            return parseLong(new String(bytes, StandardCharsets.UTF_8));
        }
        return parseLong(value.toString());
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /** 집계 쿼리 결과가 {@code null} 이거나 조회 자체가 실패해도 0 을 돌려준다. */
    private static long nullSafe(java.util.function.Supplier<Long> supplier) {
        try {
            Long value = supplier.get();
            return value == null ? 0L : value;
        } catch (Exception ex) {
            log.debug("통계 합계 조회 실패(0 으로 처리): {}", ex.toString());
            return 0L;
        }
    }
}
