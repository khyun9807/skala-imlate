package com.skala.imlate.common.properties;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate limit 설정(`imlate.rate-limit.*`). 스코프별 토큰 버킷 규칙을 담는다.
 *
 * @param enabled                       rate limit 사용 여부
 * @param failOpen                      Redis 장애 시 로컬 리미터로 강등(true) / 429 차단(false)
 * @param global                        기본 스코프 규칙
 * @param register                      등록 API 스코프 규칙
 * @param lookup                        조회 API 스코프 규칙
 * @param trustedProxies                신뢰 프록시 IP 목록(ALB 등)
 * @param localFallbackPermitsPerMinute 로컬 폴백 리미터의 분당 허용량
 */
@ConfigurationProperties(prefix = "imlate.rate-limit")
public record RateLimitProperties(boolean enabled, boolean failOpen,
                                  Rule global, Rule register, Rule lookup,
                                  List<String> trustedProxies, int localFallbackPermitsPerMinute) {

    public RateLimitProperties {
        if (global == null) {
            global = new Rule(120, 120, 60);
        }
        if (register == null) {
            register = new Rule(8, 8, 60);
        }
        if (lookup == null) {
            lookup = new Rule(40, 40, 60);
        }
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        if (localFallbackPermitsPerMinute <= 0) {
            localFallbackPermitsPerMinute = 120;
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
