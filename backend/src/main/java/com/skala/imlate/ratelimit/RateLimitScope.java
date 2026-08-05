package com.skala.imlate.ratelimit;

import com.skala.imlate.common.properties.RateLimitProperties;

/**
 * Rate limit 적용 범위(스코프). 요청 경로/메서드에 따라 서로 다른 토큰 버킷 규칙을 적용한다.
 *
 * <p>{@link #GLOBAL} 은 모든 API 요청에 항상 적용되고, {@link #REGISTER}/{@link #LOOKUP} 은
 * GLOBAL 통과 후 추가로 검사한다. {@link #REGISTER_PERSON} 은 등록 요청이 IP 축을 통과했을 때만
 * 마지막으로 검사한다.
 *
 * <p><b>축이 둘인 이유</b> — GLOBAL·REGISTER·LOOKUP 의 식별자는 <b>IP</b> 다. 기숙사 공용 와이파이(NAT)에서는
 * 200명이 같은 공인 IP 를 쓰므로 이 축으로는 "사람"을 구분할 수 없다. 그래서 IP 축은 회선 단위 폭주만 막고,
 * 사람 단위 도배는 요청 본문에서 만든 개인 키를 쓰는 {@link #REGISTER_PERSON} 이 맡는다.
 */
public enum RateLimitScope {

    /** 모든 API 요청에 적용되는 기본 스코프(식별자: IP). */
    GLOBAL("global", "imlate:rl:global:", RateLimitScope.TOO_MANY_REQUESTS_MESSAGE),
    /** 등록 API(POST /api/v1/registrations) 스코프(식별자: IP). 회선 단위 폭주 차단용. */
    REGISTER("register", "imlate:rl:register:", RateLimitScope.TOO_MANY_REQUESTS_MESSAGE),
    /**
     * 등록 API 스코프(식별자: 개인 키 해시). 같은 사람의 도배만 막는다.
     *
     * <p>키 접두어는 SPEC §8.2 계약에 맞춰 {@code imlate:rl:register:person:} 이다
     * (설정 프로퍼티·로그의 스코프 이름은 {@code register-person}).
     */
    REGISTER_PERSON("register-person", "imlate:rl:register:person:",
            "같은 정보로 너무 자주 등록을 시도했습니다. 잠시 후 다시 시도해 주세요."),
    /** 사감 조회 API(/api/v1/lookup) 스코프(식별자: IP). */
    LOOKUP("lookup", "imlate:rl:lookup:", RateLimitScope.TOO_MANY_REQUESTS_MESSAGE);

    /** IP 축(회선 단위) 차단 문구. */
    private static final String TOO_MANY_REQUESTS_MESSAGE = "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";

    private final String key;
    private final String keyPrefix;
    private final String blockedMessage;

    /**
     * @param key            스코프 이름(설정·로그용)
     * @param keyPrefix      Redis 키 접두어. 미리 완성해 두어 요청당 문자열 연결을 1회로 줄인다
     * @param blockedMessage 차단 시 사용자 문구
     */
    RateLimitScope(String key, String keyPrefix, String blockedMessage) {
        this.key = key;
        this.keyPrefix = keyPrefix;
        this.blockedMessage = blockedMessage;
    }

    /** 스코프 식별자(소문자). 예: {@code global} */
    public String key() {
        return key;
    }

    /** {@code imlate:rl:…:} 형태의 키 접두어. */
    public String keyPrefix() {
        return keyPrefix;
    }

    /**
     * 차단됐을 때 사용자에게 보여줄 한국어 문구.
     *
     * <p>개인 축과 IP 축의 문구를 다르게 둔다 — 사용자가 "내가 너무 많이 눌렀구나"와
     * "지금 다 같이 몰렸구나"를 구분할 수 있어야 다음 행동(잠깐 기다리기 vs 새로고침)이 달라진다.
     */
    public String blockedMessage() {
        return blockedMessage;
    }

    /**
     * 클라이언트 식별자를 붙여 최종 버킷 키를 만든다.
     *
     * @param clientId 클라이언트 IP 또는 개인 키 해시
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
            case REGISTER_PERSON -> properties.registerPerson();
            case LOOKUP -> properties.lookup();
        };
    }
}
