package com.skala.imlate.ratelimit;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.imlate.common.error.ErrorCode;
import com.skala.imlate.common.error.ErrorResponse;
import com.skala.imlate.common.properties.RateLimitProperties;
import com.skala.imlate.common.web.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code /api/**} 요청에 토큰 버킷 rate limit 을 적용하는 인터셉터 (R14).
 *
 * <p>스코프는 GLOBAL(모든 요청) + REGISTER/LOOKUP(경로별 추가 검사) 2단계다.
 * 따라서 Redis 호출은 요청당 최대 2회다.
 *
 * <p><b>중요:</b> 인터셉터 내부에서 발생한 어떤 예외도 요청을 실패시키지 않는다.
 * rate limit 은 부가 기능이며 서비스 가용성을 해쳐서는 안 된다(SPEC §14).
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private static final String HEADER_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RESET = "X-RateLimit-Reset";
    private static final String HEADER_RETRY_AFTER = "Retry-After";
    private static final String CONTENT_TYPE_JSON_UTF8 = "application/json;charset=UTF-8";

    private static final String METHOD_OPTIONS = "OPTIONS";
    private static final String METHOD_POST = "POST";
    private static final String PATH_REGISTRATIONS = "/api/v1/registrations";
    private static final String PATH_LOOKUP = "/api/v1/lookup";
    private static final String PATH_ACTUATOR = "/actuator";

    /** 차단 시 사용자에게 보여줄 한국어 문구. */
    private static final String BLOCKED_MESSAGE = "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";

    /** 차단 WARN 로그 요약 주기(1분). */
    private static final long WARN_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final CompositeRateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final AtomicLong blockedCount = new AtomicLong(0L);
    private final AtomicLong nextWarnAtMillis = new AtomicLong(0L);

    public RateLimitInterceptor(CompositeRateLimiter rateLimiter,
                                RateLimitProperties properties,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            if (!properties.enabled()) {
                return true;
            }
            if (METHOD_OPTIONS.equals(request.getMethod())) {
                // CORS preflight 는 계산 대상에서 제외한다.
                return true;
            }

            String path = pathWithinApplication(request);
            if (path.startsWith(PATH_ACTUATOR)) {
                return true;
            }

            long nowMillis = clock.millis();
            // IP 판별 실패 시 ClientIpResolver.UNKNOWN("unknown") 이 그대로 키가 된다.
            // 즉 미확인 클라이언트끼리 하나의 버킷을 공유한다 — 우회 통로를 만들지 않는 쪽이 안전하다.
            String clientId = ClientIpResolver.resolve(request);

            // 1) GLOBAL 은 모든 요청에 항상 적용한다.
            RateLimitDecision decision = consume(RateLimitScope.GLOBAL, clientId, nowMillis);
            RateLimitScope blockedScope = RateLimitScope.GLOBAL;

            // 2) 경로별 스코프가 따로 있으면 GLOBAL 통과 후 추가로 검사한다.
            if (decision.allowed()) {
                RateLimitScope scope = resolveScope(request.getMethod(), path);
                if (scope != RateLimitScope.GLOBAL) {
                    decision = consume(scope, clientId, nowMillis);
                    blockedScope = scope;
                }
            }

            writeRateLimitHeaders(response, decision, nowMillis);

            if (decision.allowed()) {
                return true;
            }
            reject(request, response, decision, blockedScope, clientId, path, nowMillis);
            return false;
        } catch (Exception e) {
            // rate limit 실패가 서비스 장애로 번지지 않게 한다.
            log.warn("rate limit 처리 중 예외 발생 — 요청은 통과시킨다.", e);
            return true;
        }
    }

    private RateLimitDecision consume(RateLimitScope scope, String clientId, long nowMillis) {
        return rateLimiter.tryConsume(scope.bucketKey(clientId), scope.rule(properties), nowMillis);
    }

    /**
     * 요청 경로에 맞는 스코프를 고른다.
     *
     * <ul>
     *   <li>{@code POST /api/v1/registrations} → REGISTER</li>
     *   <li>{@code /api/v1/lookup...} → LOOKUP</li>
     *   <li>그 외 → GLOBAL</li>
     * </ul>
     */
    private static RateLimitScope resolveScope(String method, String path) {
        if (METHOD_POST.equals(method) && path.startsWith(PATH_REGISTRATIONS)) {
            return RateLimitScope.REGISTER;
        }
        if (path.startsWith(PATH_LOOKUP)) {
            return RateLimitScope.LOOKUP;
        }
        return RateLimitScope.GLOBAL;
    }

    /** 컨텍스트 패스를 제외한 요청 경로. 대부분 컨텍스트 패스가 비어 있어 추가 할당이 없다. */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty() || !uri.startsWith(contextPath)) {
            return uri;
        }
        String path = uri.substring(contextPath.length());
        return path.isEmpty() ? "/" : path;
    }

    private static void writeRateLimitHeaders(HttpServletResponse response, RateLimitDecision decision,
                                              long nowMillis) {
        response.setHeader(HEADER_LIMIT, Long.toString(decision.limit()));
        response.setHeader(HEADER_REMAINING, Long.toString(decision.remaining()));
        response.setHeader(HEADER_RESET, Long.toString(decision.resetEpochSeconds(nowMillis)));
    }

    /** 429 응답 본문을 직접 작성한다(컨트롤러에 도달하지 않으므로 예외 핸들러를 탈 수 없다). */
    private void reject(HttpServletRequest request, HttpServletResponse response, RateLimitDecision decision,
                        RateLimitScope scope, String clientId, String path, long nowMillis) {
        logBlocked(scope, clientId, path, nowMillis);

        if (response.isCommitted()) {
            return;
        }
        // response.reset() 은 CorsInterceptor 가 먼저 넣어 둔 Access-Control-* 헤더까지 지워
        // 브라우저가 429 본문을 읽지 못하게 만든다. 따라서 초기화하지 않고 필요한 값만 덮어쓴다.
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(CONTENT_TYPE_JSON_UTF8);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HEADER_RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        writeRateLimitHeaders(response, decision, nowMillis);

        ErrorResponse body = ErrorResponse.of(ErrorCode.RATE_LIMITED, BLOCKED_MESSAGE,
                request.getRequestURI(), OffsetDateTime.now(clock));
        try {
            // ObjectMapper 는 기본적으로 UTF-8 로 쓰고 스트림을 닫는다(= 응답 커밋).
            objectMapper.writeValue(response.getOutputStream(), body);
        } catch (Exception e) {
            log.debug("429 응답 본문 작성 실패 (path={})", path, e);
        }
    }

    /** 차단 로그: 상세는 DEBUG, 운영 가시성을 위한 요약 WARN 은 1분에 1회만 남긴다. */
    private void logBlocked(RateLimitScope scope, String clientId, String path, long nowMillis) {
        long total = blockedCount.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("rate limited: scope={}, client={}, path={}", scope.key(), clientId, path);
        }
        long next = nextWarnAtMillis.get();
        if (nowMillis >= next && nextWarnAtMillis.compareAndSet(next, nowMillis + WARN_INTERVAL_MILLIS)) {
            log.warn("rate limit 차단 발생: scope={}, path={}, 기동 후 누적 차단 {}건", scope.key(), path, total);
        }
    }
}
