package com.skala.imlate.stats.collector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.skala.imlate.common.web.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code /api/**} 요청마다 방문 통계를 남기는 인터셉터(SPEC §7.1).
 *
 * <p>방문자 식별은 프론트가 붙이는 {@code X-Visitor-Id}(localStorage UUID) 를 우선 사용하고,
 * 없으면 {@code sha256(clientIp + "|" + 일자)} 앞 16자를 쓴다. 일자를 솔트로 섞어
 * 날짜가 바뀌면 식별자도 바뀌므로 장기 추적이 불가능하다(프라이버시 보호).
 *
 * <p><b>이 인터셉터는 절대 요청을 실패시키지 않는다.</b> 모든 예외를 삼키고 항상 {@code true} 를 반환한다.
 */
@Component
public class StatsInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StatsInterceptor.class);

    /** 프론트가 붙이는 방문자 식별 헤더. */
    public static final String VISITOR_ID_HEADER = "X-Visitor-Id";

    private static final String API_PREFIX = "/api/";
    /** 통계 조회 API 자체는 집계에서 제외한다(통계가 통계를 부풀리지 않도록). */
    private static final String STATS_PREFIX = "/api/v1/stats";
    private static final int VISITOR_ID_MAX_LENGTH = 64;
    private static final int VISITOR_ID_MIN_LENGTH = 8;
    private static final int DERIVED_ID_LENGTH = 16;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final StatsRecorder statsRecorder;
    private final Clock clock;

    public StatsInterceptor(StatsRecorder statsRecorder, Clock clock) {
        this.statsRecorder = statsRecorder;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            if (!statsRecorder.isEnabled()) {
                return true;
            }
            // CORS preflight 는 사용자의 방문이 아니다.
            if (HttpMethod.OPTIONS.matches(request.getMethod())) {
                return true;
            }
            if (!isCountable(request)) {
                return true;
            }
            LocalDate today = LocalDate.now(clock);
            statsRecorder.recordVisit(resolveVisitorId(request, today), today);
        } catch (Exception ex) {
            log.debug("통계 인터셉터 오류(요청에는 영향 없음): {}", ex.toString());
        }
        return true;
    }

    /** actuator·정적 리소스·통계 조회 API 를 제외한 {@code /api/**} 요청만 집계 대상이다. */
    private boolean isCountable(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        if (path == null || !path.startsWith(API_PREFIX)) {
            return false;
        }
        return !path.startsWith(STATS_PREFIX);
    }

    /** 컨텍스트 경로를 제외한 요청 경로. */
    private String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            String stripped = uri.substring(contextPath.length());
            return stripped.isEmpty() ? "/" : stripped;
        }
        return uri;
    }

    /** 방문자 식별자 결정: 헤더 우선, 없으면 IP 해시. */
    private String resolveVisitorId(HttpServletRequest request, LocalDate date) {
        String fromHeader = sanitize(request.getHeader(VISITOR_ID_HEADER));
        if (fromHeader != null) {
            return fromHeader;
        }
        String clientIp = ClientIpResolver.resolve(request);
        return shortHash(clientIp + "|" + StatsKeys.day(date));
    }

    /**
     * 헤더 값을 안전한 식별자로 정제한다.
     *
     * <p>영숫자와 {@code -}, {@code _} 만 남기고 길이를 제한해 임의 값이 Redis 로 흘러드는 것을 막는다.
     *
     * @return 신뢰할 수 없거나 너무 짧으면 {@code null}
     */
    private static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(Math.min(trimmed.length(), VISITOR_ID_MAX_LENGTH));
        for (int i = 0; i < trimmed.length() && builder.length() < VISITOR_ID_MAX_LENGTH; i++) {
            char ch = trimmed.charAt(i);
            boolean allowed = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
            if (allowed) {
                builder.append(ch);
            }
        }
        return builder.length() >= VISITOR_ID_MIN_LENGTH ? builder.toString() : null;
    }

    /** SHA-256 해시의 앞 {@value #DERIVED_ID_LENGTH} 자(16진). */
    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(DERIVED_ID_LENGTH);
            for (int i = 0; i < digest.length && builder.length() < DERIVED_ID_LENGTH; i++) {
                builder.append(HEX[(digest[i] >> 4) & 0x0F]).append(HEX[digest[i] & 0x0F]);
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 은 표준 JDK 에 항상 존재하지만, 없더라도 PV 만 집계하고 넘어간다.
            log.debug("SHA-256 미지원으로 방문자 식별자를 만들 수 없습니다.");
            return null;
        }
    }
}
