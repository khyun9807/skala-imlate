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
 * <p><b>2축(IP · 사람) 3단 구조</b> — SPEC §8.2. 앞 단에서 막히면 뒤 단은 호출하지 않는다.
 * <ol>
 *   <li>GLOBAL(IP) — 모든 요청</li>
 *   <li>REGISTER(IP) / LOOKUP(IP) — 경로별 추가 검사</li>
 *   <li>REGISTER_PERSON(개인 키) — 등록 요청이 위 두 단계를 통과했을 때만</li>
 * </ol>
 * Redis 호출은 일반 요청 1회, 조회 2회, <b>등록 3회</b>다. 등록에서 1회가 늘어난 이유는
 * 공용 와이파이(NAT) 때문에 IP 만으로는 "사람"을 구분할 수 없기 때문이다. IP 축만으로 사람을 막으려 하면
 * 9번째 학생부터 429 가 되어 서비스가 성립하지 않는다. 늘어난 1회는 등록 경로에만 붙고,
 * 등록은 어차피 뒤에 DB 왕복이 따르므로 체감 지연은 사실상 없다.
 *
 * <p><b>IP 판별</b>은 {@link ClientIpResolver#resolve(HttpServletRequest, ClientIpResolver.TrustedProxies)}
 * 로 한다. {@code imlate.rate-limit.trusted-proxies} 가 비어 있으면 {@code X-Forwarded-For} 를 무시하므로,
 * 공격자가 헤더를 바꿔 가며 IP 버킷을 무한히 만들어 내는 우회가 불가능하다.
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

    /** 차단 WARN 로그 요약 주기(1분). */
    private static final long WARN_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final CompositeRateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final PersonKeyResolver personKeyResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 설정을 기동 시 한 번만 파싱해 둔다(요청마다 CIDR 문자열을 다시 파싱하지 않는다). */
    private final ClientIpResolver.TrustedProxies trustedProxies;

    private final AtomicLong blockedCount = new AtomicLong(0L);
    private final AtomicLong nextWarnAtMillis = new AtomicLong(0L);

    public RateLimitInterceptor(CompositeRateLimiter rateLimiter,
                                RateLimitProperties properties,
                                PersonKeyResolver personKeyResolver,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.personKeyResolver = personKeyResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.trustedProxies = ClientIpResolver.TrustedProxies.of(properties.trustedProxies());
    }

    /** 신뢰 프록시 설정 개수(기동 로그·진단용). */
    public int trustedProxyCount() {
        return trustedProxies.size();
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
            String clientIp = ClientIpResolver.resolve(request, trustedProxies);

            // 1) GLOBAL 은 모든 요청에 항상 적용한다.
            RateLimitDecision decision = consume(RateLimitScope.GLOBAL, clientIp, nowMillis);
            RateLimitScope blockedScope = RateLimitScope.GLOBAL;
            String blockedId = clientIp;

            // 2) 경로별 스코프가 따로 있으면 GLOBAL 통과 후 추가로 검사한다.
            if (decision.allowed()) {
                RateLimitScope scope = resolveScope(request.getMethod(), path);
                if (scope != RateLimitScope.GLOBAL) {
                    decision = consume(scope, clientIp, nowMillis);
                    blockedScope = scope;
                }

                // 3) 등록 요청은 IP 축을 통과한 뒤 "같은 사람" 축을 한 번 더 본다.
                //    공용 와이파이에서 IP 축은 사람을 구분하지 못하므로 도배 차단은 이 단계가 담당한다.
                if (decision.allowed() && scope == RateLimitScope.REGISTER) {
                    String personId = personKeyResolver.resolve(request);
                    if (personId != null) {
                        decision = consume(RateLimitScope.REGISTER_PERSON, personId, nowMillis);
                        blockedScope = RateLimitScope.REGISTER_PERSON;
                        blockedId = personId;
                    }
                }
            }

            // 헤더에는 마지막으로 검사한(= 가장 좁은) 버킷의 값을 싣는다. 클라이언트에게는
            // 자신을 실제로 막게 될 한도를 알려주는 편이 유용하다.
            writeRateLimitHeaders(response, decision, nowMillis);

            if (decision.allowed()) {
                return true;
            }
            reject(request, response, decision, blockedScope, blockedId, path, nowMillis);
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

    /**
     * 429 응답 본문을 직접 작성한다(컨트롤러에 도달하지 않으므로 예외 핸들러를 탈 수 없다).
     *
     * <p>문구만 스코프에 따라 달라지고, 상태 코드·에러 코드·헤더는 IP 차단과 개인 차단이 완전히 동일하다.
     */
    private void reject(HttpServletRequest request, HttpServletResponse response, RateLimitDecision decision,
                        RateLimitScope scope, String blockedId, String path, long nowMillis) {
        logBlocked(scope, blockedId, path, nowMillis);

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

        ErrorResponse body = ErrorResponse.of(ErrorCode.RATE_LIMITED, scope.blockedMessage(),
                request.getRequestURI(), OffsetDateTime.now(clock));
        try {
            // ObjectMapper 는 기본적으로 UTF-8 로 쓰고 스트림을 닫는다(= 응답 커밋).
            objectMapper.writeValue(response.getOutputStream(), body);
        } catch (Exception e) {
            log.debug("429 응답 본문 작성 실패 (path={})", path, e);
        }
    }

    /**
     * 차단 로그: 상세는 DEBUG, 운영 가시성을 위한 요약 WARN 은 1분에 1회만 남긴다.
     *
     * <p>{@code blockedId} 는 IP 또는 개인 키 <b>해시</b>다. 개인 축이어도 이름·호수가 로그에 남지 않는다.
     */
    private void logBlocked(RateLimitScope scope, String blockedId, String path, long nowMillis) {
        long total = blockedCount.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("rate limited: scope={}, client={}, path={}", scope.key(), blockedId, path);
        }
        long next = nextWarnAtMillis.get();
        if (nowMillis >= next && nextWarnAtMillis.compareAndSet(next, nowMillis + WARN_INTERVAL_MILLIS)) {
            log.warn("rate limit 차단 발생: scope={}, path={}, 기동 후 누적 차단 {}건", scope.key(), path, total);
        }
    }
}
