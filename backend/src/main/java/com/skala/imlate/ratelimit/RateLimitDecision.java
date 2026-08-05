package com.skala.imlate.ratelimit;

/**
 * Rate limit 판정 결과. 응답 헤더({@code X-RateLimit-*}, {@code Retry-After}) 생성에 필요한 값을 모두 담는다.
 *
 * @param allowed          허용 여부
 * @param limit            해당 스코프의 최대 허용량(버킷 capacity)
 * @param remaining        남은 토큰 수(0 이상)
 * @param retryAfterMillis 차단 시 재시도까지 대기할 밀리초(허용 시 0)
 * @param resetMillis      버킷이 완전히 회복되기까지 남은 밀리초
 */
public record RateLimitDecision(boolean allowed, long limit, long remaining,
                                long retryAfterMillis, long resetMillis) {

    public RateLimitDecision {
        if (limit < 0) {
            limit = 0;
        }
        if (remaining < 0) {
            remaining = 0;
        }
        if (retryAfterMillis < 0) {
            retryAfterMillis = 0;
        }
        if (resetMillis < 0) {
            resetMillis = 0;
        }
    }

    /** 허용 결과를 만든다. */
    public static RateLimitDecision allow(long limit, long remaining, long resetMillis) {
        return new RateLimitDecision(true, limit, remaining, 0L, resetMillis);
    }

    /** 차단 결과를 만든다(남은 토큰 0). */
    public static RateLimitDecision deny(long limit, long retryAfterMillis, long resetMillis) {
        return new RateLimitDecision(false, limit, 0L, retryAfterMillis, resetMillis);
    }

    /**
     * {@code Retry-After} 헤더용 초 단위 값. 올림 처리하며 차단 시 최소 1초를 보장한다.
     */
    public long retryAfterSeconds() {
        if (allowed) {
            return 0L;
        }
        long seconds = (retryAfterMillis + 999L) / 1000L;
        return Math.max(seconds, 1L);
    }

    /**
     * {@code X-RateLimit-Reset} 헤더용 epoch 초 값.
     *
     * @param nowMillis 판정 시각(epoch milli)
     * @return 버킷이 회복되는 시각의 epoch 초(올림)
     */
    public long resetEpochSeconds(long nowMillis) {
        return (nowMillis + resetMillis + 999L) / 1000L;
    }
}
