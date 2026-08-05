package com.skala.imlate.ratelimit;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * 토큰 버킷 Lua 스크립트 빈 설정.
 *
 * <p>{@link DefaultRedisScript} 는 스크립트 본문의 SHA1 을 캐싱하므로 실제 호출은 {@code EVALSHA} 1회로 끝난다.
 */
@Configuration
public class RateLimitScriptConfig {

    /** 스크립트 리소스 경로. */
    public static final String SCRIPT_LOCATION = "redis/rate_limit_token_bucket.lua";

    /**
     * {@code rate_limit_token_bucket.lua} 를 실행할 스크립트 빈.
     *
     * <p>반환 타입은 {@code {allowed, remaining, retryAfterMs, resetMs}} 정수 4개 배열이므로 {@link List} 다.
     */
    @Bean("rateLimitTokenBucketScript")
    @SuppressWarnings("rawtypes")
    public DefaultRedisScript<List> rateLimitTokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(SCRIPT_LOCATION)));
        script.setResultType(List.class);
        return script;
    }
}
