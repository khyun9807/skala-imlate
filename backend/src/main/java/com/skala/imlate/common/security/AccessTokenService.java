package com.skala.imlate.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.error.ApiException;
import com.skala.imlate.common.error.ErrorCode;
import com.skala.imlate.common.properties.ImlateProperties;

/**
 * 사감 조회 페이지 접근 토큰 발급/검증기.
 *
 * <p>토큰 포맷: {@code base64url(expEpochSeconds) + "." + base64url(hmacSha256(secret, date + ":" + exp))}
 * (Base64 URL-safe, 패딩 없음)
 */
@Component
public class AccessTokenService {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenService.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final char SEPARATOR = '.';
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    /** 시크릿 미설정 시 로컬 개발 편의를 위한 대체 값(운영에서는 절대 사용 금지). */
    private static final String FALLBACK_SECRET = "imlate-local-dev-secret-do-not-use-in-production";

    private final byte[] secretKey;
    private final long tokenTtlSeconds;
    private final Clock clock;

    @Autowired
    public AccessTokenService(ImlateProperties properties, Clock clock, Environment environment) {
        this.clock = clock;
        this.tokenTtlSeconds = Math.max(1L, properties.lookup().tokenTtlHours()) * 3600L;

        String configured = properties.lookup().tokenSecret();
        if (configured == null || configured.isBlank()) {
            if (isProdProfile(environment)) {
                log.error("imlate.lookup.token-secret 이 비어 있습니다. "
                        + "운영 환경에서는 IMLATE_LOOKUP_TOKEN_SECRET 을 반드시 설정하세요. 임시 시크릿으로 기동합니다.");
            } else {
                log.warn("imlate.lookup.token-secret 이 비어 있어 로컬 개발용 임시 시크릿을 사용합니다.");
            }
            this.secretKey = FALLBACK_SECRET.getBytes(StandardCharsets.UTF_8);
        } else {
            this.secretKey = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** 테스트/수동 조립용 생성자. */
    public AccessTokenService(ImlateProperties properties, Clock clock) {
        this(properties, clock, null);
    }

    /** 지정 날짜에 대한 조회 토큰을 발급한다. */
    public String issue(LocalDate date) {
        long exp = Instant.now(clock).plus(tokenTtlSeconds, ChronoUnit.SECONDS).getEpochSecond();
        String expPart = ENCODER.encodeToString(Long.toString(exp).getBytes(StandardCharsets.UTF_8));
        String signaturePart = ENCODER.encodeToString(sign(date, exp));
        return expPart + SEPARATOR + signaturePart;
    }

    /** 유효하면 true. 서명 불일치·만료·형식 오류는 모두 false. */
    public boolean verify(LocalDate date, String token) {
        if (date == null || token == null || token.isBlank()) {
            return false;
        }
        String trimmed = token.trim();
        int separatorIndex = trimmed.indexOf(SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex == trimmed.length() - 1) {
            return false;
        }
        String expPart = trimmed.substring(0, separatorIndex);
        String signaturePart = trimmed.substring(separatorIndex + 1);
        if (signaturePart.indexOf(SEPARATOR) >= 0) {
            return false;
        }

        long exp;
        try {
            exp = Long.parseLong(new String(DECODER.decode(expPart), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            log.debug("Malformed access token exp part");
            return false;
        }
        if (exp < Instant.now(clock).getEpochSecond()) {
            log.debug("Access token expired: exp={}", exp);
            return false;
        }

        // 서명은 정규화된 base64url 문자열끼리 비교한다.
        // (바이트로 디코딩해 비교하면 마지막 문자의 잉여 비트 차이를 같은 값으로 취급하게 된다.)
        String expected = ENCODER.encodeToString(sign(date, exp));
        // 타이밍 공격 방지를 위한 상수시간 비교
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signaturePart.getBytes(StandardCharsets.UTF_8));
    }

    /** 검증 실패 시 {@link ErrorCode#FORBIDDEN} 예외를 던진다. */
    public void requireValid(LocalDate date, String token) {
        if (!verify(date, token)) {
            throw ApiException.of(ErrorCode.FORBIDDEN, "조회 링크가 유효하지 않거나 만료되었습니다. 사감님께 재발급을 요청해 주세요.");
        }
    }

    private byte[] sign(LocalDate date, long exp) {
        String payload = date + ":" + exp;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            // 알고리즘/키 문제는 설정 오류이므로 내부 오류로 올린다.
            throw ApiException.of(ErrorCode.INTERNAL_ERROR, "조회 토큰을 처리할 수 없습니다.", ex);
        }
    }

    private static boolean isProdProfile(Environment environment) {
        if (environment == null) {
            return false;
        }
        String[] profiles = environment.getActiveProfiles();
        return Arrays.stream(profiles).anyMatch(profile -> profile.equalsIgnoreCase("prod"));
    }
}
