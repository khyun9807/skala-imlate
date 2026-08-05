package com.skala.imlate.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.RateLimitProperties;

/**
 * 기본은 Redis 토큰 버킷을 쓰고, Redis 장애 시 설정에 따라 강등/차단하는 rate limiter.
 *
 * <ul>
 *   <li>{@code fail-open=true} : 로컬 인메모리 리미터로 강등(가용성 우선, 기본값)</li>
 *   <li>{@code fail-open=false} : 429 로 차단(보호 우선)</li>
 * </ul>
 *
 * <p>장애 시 요청마다 WARN 로그가 폭주하지 않도록 경고는 1분에 1회만 남긴다.
 */
@Component
@Primary
public class CompositeRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(CompositeRateLimiter.class);

    /** 동일 장애에 대한 경고 로그 최소 간격(1분). */
    private static final long WARN_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final RedisRateLimiter redisRateLimiter;
    private final LocalFallbackRateLimiter localFallbackRateLimiter;
    private final RateLimitProperties properties;
    private final AtomicLong nextWarnAtMillis = new AtomicLong(0L);

    public CompositeRateLimiter(RedisRateLimiter redisRateLimiter,
                                LocalFallbackRateLimiter localFallbackRateLimiter,
                                RateLimitProperties properties) {
        this.redisRateLimiter = redisRateLimiter;
        this.localFallbackRateLimiter = localFallbackRateLimiter;
        this.properties = properties;
    }

    @Override
    public RateLimitDecision tryConsume(String key, RateLimitProperties.Rule rule, long nowMillis) {
        try {
            return redisRateLimiter.tryConsume(key, rule, nowMillis);
        } catch (RuntimeException e) {
            // RateLimitBackendException 을 포함한 모든 런타임 예외를 여기서 흡수한다.
            warnThrottled(e, nowMillis);
            if (properties.failOpen()) {
                return localFallbackRateLimiter.tryConsume(key, rule, nowMillis);
            }
            long waitMillis = TimeUnit.SECONDS.toMillis(rule.refillPeriodSeconds());
            return RateLimitDecision.deny(rule.capacity(), waitMillis, waitMillis);
        }
    }

    @Override
    public String name() {
        return "composite";
    }

    /** 1분에 1회만 경고를 남긴다(장애 시 로그 폭주 방지). */
    private void warnThrottled(RuntimeException cause, long nowMillis) {
        long next = nextWarnAtMillis.get();
        if (nowMillis < next || !nextWarnAtMillis.compareAndSet(next, nowMillis + WARN_INTERVAL_MILLIS)) {
            if (log.isDebugEnabled()) {
                log.debug("rate limit backend 오류(로그 축약됨): {}", cause.toString());
            }
            return;
        }
        String mode = properties.failOpen() ? "local fallback 으로 강등" : "429 차단(fail-closed)";
        log.warn("Redis rate limit 백엔드 오류 — {}. 이후 1분간 동일 경고는 생략한다.", mode, cause);
    }
}
