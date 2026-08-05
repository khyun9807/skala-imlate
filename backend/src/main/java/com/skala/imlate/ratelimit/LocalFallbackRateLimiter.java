package com.skala.imlate.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.RateLimitProperties;

/**
 * Redis 장애 시 사용하는 인메모리 폴백 rate limiter.
 *
 * <p>정확도보다 "장애 중에도 폭주를 막는 것"이 목적이므로 1분 고정 윈도우 카운터를 쓴다.
 * 인스턴스별로 독립 카운트되므로 다중 인스턴스에서는 허용량이 인스턴스 수만큼 늘어날 수 있으나,
 * 어차피 축소 운영(degraded) 상태에서의 안전망이다.
 *
 * <p>메모리 누수 방지: 항목 수 상한({@value #MAX_ENTRIES})과 주기적 정리를 함께 적용한다.
 */
@Component
public class LocalFallbackRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LocalFallbackRateLimiter.class);

    /** 고정 윈도우 길이(1분). */
    private static final long WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(1);
    /** 맵 항목 수 상한. 초과하면 즉시 정리하고, 그래도 넘치면 전부 비운다. */
    private static final int MAX_ENTRIES = 100_000;
    /** 정리 주기(1분). */
    private static final long SWEEP_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);
    /** 이 시간 동안 접근이 없으면 오래된 항목으로 보고 제거한다(윈도우 2개분). */
    private static final long STALE_AFTER_MILLIS = WINDOW_MILLIS * 2;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final AtomicLong nextSweepAtMillis = new AtomicLong(0L);
    private final RateLimitProperties properties;

    public LocalFallbackRateLimiter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public RateLimitDecision tryConsume(String key, RateLimitProperties.Rule rule, long nowMillis) {
        long permits = permitsPerMinute(rule);
        long windowStart = nowMillis - Math.floorMod(nowMillis, WINDOW_MILLIS);
        long resetMillis = windowStart + WINDOW_MILLIS - nowMillis;

        // ConcurrentHashMap.compute 는 키 단위로 원자적이므로 증가와 윈도우 롤오버를 한 번에 처리한다.
        long[] usedHolder = new long[1];
        counters.compute(key, (k, existing) -> {
            WindowCounter counter = (existing == null || existing.windowStartMillis != windowStart)
                    ? new WindowCounter(windowStart)
                    : existing;
            counter.used++;
            counter.lastAccessMillis = nowMillis;
            usedHolder[0] = counter.used;
            return counter;
        });

        sweepIfNeeded(nowMillis);

        long used = usedHolder[0];
        if (used > permits) {
            // 차단된 요청도 카운트에 남겨 둔다(공격 트래픽이 윈도우를 계속 채우도록).
            return RateLimitDecision.deny(permits, resetMillis, resetMillis);
        }
        return RateLimitDecision.allow(permits, permits - used, resetMillis);
    }

    @Override
    public String name() {
        return "local";
    }

    /** 현재 추적 중인 키 개수(진단·테스트용). */
    public int trackedKeyCount() {
        return counters.size();
    }

    /**
     * 스코프 규칙에서 분당 허용량을 계산한다.
     *
     * <p>규칙이 의도한 분당 허용량과 설정된 폴백 상한 중 <b>작은 값</b>을 쓴다.
     * (예: register 규칙 8/분 → 폴백 상한이 120 이어도 8 을 적용)
     */
    private long permitsPerMinute(RateLimitProperties.Rule rule) {
        long periodMillis = TimeUnit.SECONDS.toMillis(rule.refillPeriodSeconds());
        long refillPerMinute = periodMillis <= 0
                ? rule.capacity()
                : Math.max(1L, (rule.refillTokens() * WINDOW_MILLIS) / periodMillis);
        long fromRule = Math.max(rule.capacity(), refillPerMinute);
        return Math.max(1L, Math.min(fromRule, properties.localFallbackPermitsPerMinute()));
    }

    /**
     * 오래된 항목을 정리한다. 요청 스레드에 얹어 실행하되 CAS 로 동시에 한 스레드만 수행한다
     * (별도 스케줄러 스레드를 쓰지 않아 공용 스케줄러 풀을 점유하지 않는다).
     */
    private void sweepIfNeeded(long nowMillis) {
        boolean overCapacity = counters.size() > MAX_ENTRIES;
        long next = nextSweepAtMillis.get();
        if (!overCapacity && nowMillis < next) {
            return;
        }
        if (!nextSweepAtMillis.compareAndSet(next, nowMillis + SWEEP_INTERVAL_MILLIS)) {
            // 다른 스레드가 이미 정리 중이다.
            return;
        }

        long staleBefore = nowMillis - STALE_AFTER_MILLIS;
        counters.entrySet().removeIf(entry -> entry.getValue().lastAccessMillis < staleBefore);

        if (counters.size() > MAX_ENTRIES) {
            // 최후 수단: 상한을 계속 넘으면 전부 비운다(메모리 보호 > 판정 정확도).
            log.warn("local rate limit 맵이 상한({})을 초과하여 전체 초기화한다. size={}", MAX_ENTRIES, counters.size());
            counters.clear();
        }
    }

    /** 키 하나의 고정 윈도우 카운터. 갱신은 {@link Map#compute} 안에서만 이뤄진다. */
    private static final class WindowCounter {

        private final long windowStartMillis;
        private long used;
        private volatile long lastAccessMillis;

        private WindowCounter(long windowStartMillis) {
            this.windowStartMillis = windowStartMillis;
            this.lastAccessMillis = windowStartMillis;
        }
    }
}
