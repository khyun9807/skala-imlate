package com.skala.imlate.ratelimit;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.RateLimitProperties;

/**
 * Redis Lua 토큰 버킷 기반 rate limiter. 판정 1회당 Redis 왕복은 1회(EVALSHA)다.
 *
 * <p>Redis 오류는 삼키지 않고 {@link RateLimitBackendException} 으로 감싸 던진다.
 * fail-open/fail-closed 결정은 {@link CompositeRateLimiter} 의 책임이다.
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    /** 이번 요청이 소비할 토큰 수. 현재는 항상 1개다. */
    private static final String REQUESTED_TOKENS = "1";

    private final StringRedisTemplate redisTemplate;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script;

    @SuppressWarnings("rawtypes")
    public RedisRateLimiter(StringRedisTemplate redisTemplate,
                            @Qualifier("rateLimitTokenBucketScript") DefaultRedisScript<List> script) {
        this.redisTemplate = redisTemplate;
        this.script = script;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public RateLimitDecision tryConsume(String key, RateLimitProperties.Rule rule, long nowMillis) {
        long capacity = rule.capacity();
        long refillPeriodMillis = TimeUnit.SECONDS.toMillis(rule.refillPeriodSeconds());

        List result;
        try {
            result = redisTemplate.execute(script,
                    Collections.singletonList(key),
                    Long.toString(capacity),
                    Long.toString(rule.refillTokens()),
                    Long.toString(refillPeriodMillis),
                    Long.toString(nowMillis),
                    REQUESTED_TOKENS);
        } catch (RuntimeException e) {
            throw new RateLimitBackendException("Redis rate limit 스크립트 실행 실패 (key=" + key + ")", e);
        }

        if (result == null || result.size() < 4) {
            throw new RateLimitBackendException("Redis rate limit 스크립트가 예상과 다른 값을 반환했다 (key=" + key + ")");
        }

        long allowed = toLong(result.get(0));
        long remaining = toLong(result.get(1));
        long retryAfterMillis = toLong(result.get(2));
        long resetMillis = toLong(result.get(3));

        return allowed == 1L
                ? RateLimitDecision.allow(capacity, remaining, resetMillis)
                : RateLimitDecision.deny(capacity, retryAfterMillis, resetMillis);
    }

    @Override
    public String name() {
        return "redis";
    }

    /** Lua 반환값(Long 이 일반적이지만 문자열일 수도 있다)을 안전하게 long 으로 바꾼다. */
    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                throw new RateLimitBackendException("Redis rate limit 반환값을 해석할 수 없다: " + text, e);
            }
        }
        throw new RateLimitBackendException("Redis rate limit 반환값 타입이 올바르지 않다: " + value);
    }
}
