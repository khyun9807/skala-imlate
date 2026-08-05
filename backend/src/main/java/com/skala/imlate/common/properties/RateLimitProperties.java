package com.skala.imlate.common.properties;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate limit 설정(`imlate.rate-limit.*`). 스코프별 토큰 버킷 규칙을 담는다.
 *
 * <p><b>버킷 축이 2개인 이유(중요)</b> — 교육생 약 200명이 기숙사 공용 와이파이(NAT) 한 회선으로 등록한다.
 * 즉 전원이 <u>같은 공인 IP</u> 를 쓴다. 그래서 IP 버킷을 "사람 단위 제한"으로 쓰면
 * 9번째 학생부터 429 가 되어 서비스가 성립하지 않는다. 축을 둘로 나눈다.
 *
 * <ul>
 *   <li>{@code global} / {@code register} / {@code lookup} — <b>IP 축</b>.
 *       한 회선에서의 비정상 폭주(스크립트·DDoS)만 막는 굵은 그물이다.</li>
 *   <li>{@code registerPerson} — <b>사람 축</b>. 요청 본문의 {@code 반|이름|호수} 로 만든 키를 쓰므로
 *       공용 와이파이여도 "같은 사람의 도배"만 정확히 막는다.</li>
 * </ul>
 *
 * <p>여기 기본값은 설정 파일이 없을 때의 안전망이며, 실제 운영값은 {@code application*.yml} 에서 온다
 * (R10: 숫자를 코드에 박아 두지 않는다. 근거는 yml 주석에 함께 적혀 있다).
 *
 * @param enabled                       rate limit 사용 여부
 * @param failOpen                      Redis 장애 시 로컬 리미터로 강등(true) / 429 차단(false)
 * @param global                        기본 스코프 규칙(IP 축)
 * @param register                      등록 API 스코프 규칙(IP 축)
 * @param registerPerson                등록 API 스코프 규칙(사람 축, 요청 본문 기준)
 * @param lookup                        조회 API 스코프 규칙(IP 축)
 * @param trustedProxies                신뢰 프록시 IP/CIDR 목록. <b>비어 있으면 {@code X-Forwarded-For} 를
 *                                      신뢰하지 않고 {@code remoteAddr} 만 쓴다</b> — 헤더 위조로 IP 버킷을
 *                                      무한 우회하는 것을 막기 위한 기본값이다
 * @param localFallbackPermitsPerMinute 로컬 폴백 리미터의 분당 허용량(키 1개 기준)
 */
@ConfigurationProperties(prefix = "imlate.rate-limit")
public record RateLimitProperties(boolean enabled, boolean failOpen,
                                  Rule global, Rule register, Rule registerPerson, Rule lookup,
                                  List<String> trustedProxies, int localFallbackPermitsPerMinute) {

    public RateLimitProperties {
        if (global == null) {
            // 200명 × 6회/분(창 조회 + 요약 + 탭 복귀 재조회 + 등록 재시도) ≈ 1,200.
            global = new Rule(1200, 1200, 60);
        }
        if (register == null) {
            // 200명 × 1.5회(오타 수정·새로고침 재제출 여유) = 300.
            register = new Rule(300, 300, 60);
        }
        if (registerPerson == null) {
            // 같은 사람은 1~3회면 끝난다. 10회를 넘기면 사람이 아니라 스크립트다.
            registerPerson = new Rule(10, 10, 60);
        }
        if (lookup == null) {
            // 사감 2명이 같은 회선을 쓸 수 있으므로 IP 축 기준으로 넉넉히 둔다.
            lookup = new Rule(120, 120, 60);
        }
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        if (localFallbackPermitsPerMinute <= 0) {
            // Redis 장애 중에도 공용 와이파이 전체가 막히면 안 되므로 global 과 같은 수준으로 둔다.
            localFallbackPermitsPerMinute = 1200;
        }
    }

    /**
     * 토큰 버킷 규칙 1개.
     *
     * @param capacity            버킷 최대 토큰 수
     * @param refillTokens        보충 주기마다 채워지는 토큰 수
     * @param refillPeriodSeconds 보충 주기(초)
     */
    public record Rule(long capacity, long refillTokens, long refillPeriodSeconds) {

        public Rule {
            if (capacity <= 0) {
                capacity = 120L;
            }
            if (refillTokens <= 0) {
                refillTokens = capacity;
            }
            if (refillPeriodSeconds <= 0) {
                refillPeriodSeconds = 60L;
            }
        }
    }
}
