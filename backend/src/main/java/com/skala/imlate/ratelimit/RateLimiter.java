package com.skala.imlate.ratelimit;

import com.skala.imlate.common.properties.RateLimitProperties;

/**
 * Rate limit 판정기. 구현체는 Redis 토큰 버킷({@link RedisRateLimiter})과
 * 로컬 폴백({@link LocalFallbackRateLimiter}), 그리고 둘을 묶는 {@link CompositeRateLimiter} 가 있다.
 */
public interface RateLimiter {

    /**
     * 토큰 1개를 소비하며 허용 여부를 판정한다.
     *
     * <p>시각을 인자로 받는 이유는 (1) Redis Lua 스크립트를 결정적으로 유지하고,
     * (2) 한 요청에서 여러 스코프를 검사할 때 동일한 기준 시각을 쓰기 위해서다.
     *
     * @param key       버킷 키({@code imlate:rl:{scope}:{clientIp}})
     * @param rule      적용할 토큰 버킷 규칙
     * @param nowMillis 기준 시각(epoch milli, 주입된 {@link java.time.Clock} 에서 얻는다)
     * @return 판정 결과
     */
    RateLimitDecision tryConsume(String key, RateLimitProperties.Rule rule, long nowMillis);

    /** 로깅·진단용 구현체 이름. */
    String name();
}
