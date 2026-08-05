package com.skala.imlate.ratelimit;

/**
 * Rate limit 백엔드(Redis) 호출 실패를 나타내는 예외.
 *
 * <p>{@link RedisRateLimiter} 는 Redis 오류를 삼키지 않고 이 예외로 감싸 던진다.
 * 최종 처리는 {@link CompositeRateLimiter} 가 {@code imlate.rate-limit.fail-open} 설정에 따라 수행한다.
 */
public class RateLimitBackendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RateLimitBackendException(String message, Throwable cause) {
        super(message, cause);
    }

    public RateLimitBackendException(String message) {
        super(message);
    }
}
