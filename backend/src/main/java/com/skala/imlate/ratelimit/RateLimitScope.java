package com.skala.imlate.ratelimit;

import com.skala.imlate.common.properties.RateLimitProperties;

/**
 * Rate limit 적용 범위(스코프). 요청 경로/메서드에 따라 서로 다른 토큰 버킷 규칙을 적용한다.
 *
 * <p>{@link #GLOBAL} 은 모든 API 요청에 항상 적용되고, {@link #REGISTER}/{@link #LOOKUP} 은
 * GLOBAL 통과 후 추가로 검사한다.
 */
public enum RateLimitScope {

    /** 모든 API 요청에 적용되는 기본 스코프. */
    GLOBAL("global"),
    /** 등록 API(POST /api/v1/registrations) 전용 스코프 — 가장 엄격하다. */
    REGISTER("register"),
    /** 사감 조회 API(/api/v1/lookup) 전용 스코프. */
    LOOKUP("lookup");

    /** Redis 키 접두어. {@code imlate:rl:{scope}:} 까지 미리 만들어 두어 요청당 문자열 연결을 1회로 줄인다. */
    private static final String KEY_NAMESPACE = "imlate:rl:";

    private final String key;
    private final String keyPrefix;

    RateLimitScope(String key) {
        this.key = key;
        this.keyPrefix = KEY_NAMESPACE + key + ":";
    }

    /** 스코프 식별자(소문자). 예: {@code global} */
    public String key() {
        return key;
    }

    /** {@code imlate:rl:{scope}:} 형태의 키 접두어. */
    public String keyPrefix() {
        return keyPrefix;
    }

    /**
     * 클라이언트 식별자를 붙여 최종 버킷 키를 만든다.
     *
     * @param clientId 클라이언트 IP 등 식별자
     * @return {@code imlate:rl:{scope}:{clientId}}
     */
    public String bucketKey(String clientId) {
        return keyPrefix + clientId;
    }

    /**
     * 이 스코프에 대응하는 토큰 버킷 규칙을 설정에서 꺼낸다.
     *
     * @param properties rate limit 설정(모든 규칙은 null 이 아님이 보장된다)
     * @return 스코프별 규칙
     */
    public RateLimitProperties.Rule rule(RateLimitProperties properties) {
        return switch (this) {
            case GLOBAL -> properties.global();
            case REGISTER -> properties.register();
            case LOOKUP -> properties.lookup();
        };
    }
}
