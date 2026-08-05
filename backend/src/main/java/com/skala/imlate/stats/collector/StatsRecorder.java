package com.skala.imlate.stats.collector;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.StatsProperties;

/**
 * 통계 카운터의 Redis 쓰기를 캡슐화한다(SPEC §7.1).
 *
 * <p>설계 원칙 두 가지를 반드시 지킨다.
 * <ul>
 *   <li><b>성능</b>: 한 번의 기록은 파이프라인 1회(= 네트워크 왕복 1회)로 끝낸다.</li>
 *   <li><b>무해성</b>: 어떤 예외도 밖으로 던지지 않는다. 통계 실패가 서비스 장애가 되면 안 된다.</li>
 * </ul>
 */
@Component
public class StatsRecorder {

    private static final Logger log = LoggerFactory.getLogger(StatsRecorder.class);

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;

    public StatsRecorder(StringRedisTemplate redisTemplate, StatsProperties statsProperties) {
        this.redisTemplate = redisTemplate;
        this.enabled = statsProperties.enabled();
        if (!this.enabled) {
            log.info("통계 수집이 비활성화되어 있습니다(imlate.stats.enabled=false).");
        }
    }

    /** 통계 수집 사용 여부. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 방문(API 요청) 1건을 기록한다. PV 증가 + UV(HyperLogLog) 추가 + 일자 목록 갱신을 한 파이프라인으로 처리한다.
     *
     * @param visitorId 방문자 식별자. {@code null}/공백이면 UV 집계는 건너뛰고 PV 만 올린다.
     * @param date      집계 일자(KST)
     */
    public void recordVisit(String visitorId, LocalDate date) {
        if (!enabled || date == null) {
            return;
        }
        final String visitor = (visitorId == null || visitorId.isBlank()) ? null : visitorId;
        final String pageViewKey = StatsKeys.pageViews(date);
        final String visitorKey = StatsKeys.uniqueVisitors(date);
        final String day = StatsKeys.day(date);

        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection conn = (StringRedisConnection) connection;
                conn.incr(StatsKeys.PAGE_VIEWS_TOTAL);
                conn.incr(pageViewKey);
                conn.expire(pageViewKey, StatsKeys.DAILY_TTL_SECONDS);
                if (visitor != null) {
                    conn.pfAdd(StatsKeys.UNIQUE_VISITORS_TOTAL, visitor);
                    conn.pfAdd(visitorKey, visitor);
                    conn.expire(visitorKey, StatsKeys.DAILY_TTL_SECONDS);
                }
                conn.sAdd(StatsKeys.DAYS, day);
                // 파이프라인 콜백은 반드시 null 을 반환해야 한다(Spring Data Redis 규약).
                return null;
            });
        } catch (Exception ex) {
            // 통계는 부가 기능이다. 실패해도 요청 처리에 영향을 주지 않는다.
            log.debug("방문 통계 기록 실패(무시): {}", ex.toString());
        }
    }

    /**
     * 등록 1건을 기록한다. 일자별 등록 수와 누적 등록 수를 함께 올린다.
     *
     * @param date 등록 대상일(KST)
     */
    public void recordRegistration(LocalDate date) {
        if (!enabled || date == null) {
            return;
        }
        final String registrationKey = StatsKeys.registrations(date);
        final String day = StatsKeys.day(date);

        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection conn = (StringRedisConnection) connection;
                conn.incr(StatsKeys.REGISTRATIONS_TOTAL);
                conn.incr(registrationKey);
                conn.expire(registrationKey, StatsKeys.DAILY_TTL_SECONDS);
                conn.sAdd(StatsKeys.DAYS, day);
                return null;
            });
        } catch (Exception ex) {
            // 등록 자체는 이미 커밋되었으므로 통계 실패는 경고만 남긴다.
            log.warn("등록 통계 기록 실패(무시): date={}, cause={}", date, ex.toString());
        }
    }
}
