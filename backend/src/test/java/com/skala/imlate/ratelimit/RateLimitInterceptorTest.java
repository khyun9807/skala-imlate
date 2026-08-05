package com.skala.imlate.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skala.imlate.common.properties.RateLimitProperties;
import com.skala.imlate.support.TestFixtures;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@link RateLimitInterceptor} 단위 테스트.
 *
 * <p>리미터는 목으로 대체하고, 인터셉터가 만들어 내는 <b>HTTP 응답(429 · 헤더 · 본문)</b>과
 * 스코프 결정 규칙만 검증한다. 인터셉터는 어떤 경우에도 예외를 밖으로 던지지 않아야 한다.
 *
 * <p>다만 <b>공용 와이파이 시나리오</b>(같은 IP · 다른 사람)만은 목으로는 의미가 없어서,
 * 실제 토큰 버킷({@link CompositeRateLimiter} + {@link LocalFallbackRateLimiter})을 붙여
 * "정말로 200명이 통과하는가"를 센다.
 */
@DisplayName("Rate limit 인터셉터(RateLimitInterceptor)")
class RateLimitInterceptorTest {

    private static final String CLIENT_IP = "9.9.9.9";
    /** 기숙사 공용 와이파이의 공인 IP(200명이 공유한다). */
    private static final String DORM_WIFI_IP = "203.0.113.50";

    private CompositeRateLimiter rateLimiter;
    private ObjectMapper objectMapper;
    private PersonKeyResolver personKeyResolver;
    private Clock clock;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(CompositeRateLimiter.class);
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        personKeyResolver = new PersonKeyResolver(objectMapper, TestFixtures.imlateProperties());
        clock = TestFixtures.clockAt(LocalTime.of(21, 0));
    }

    private static RateLimitProperties properties(boolean enabled) {
        return properties(enabled, List.of());
    }

    private static RateLimitProperties properties(boolean enabled, List<String> trustedProxies) {
        return new RateLimitProperties(enabled, true,
                new RateLimitProperties.Rule(1200, 1200, 60),
                new RateLimitProperties.Rule(300, 300, 60),
                new RateLimitProperties.Rule(10, 10, 60),
                new RateLimitProperties.Rule(120, 120, 60),
                trustedProxies, 1200);
    }

    private RateLimitInterceptor interceptor(boolean enabled) {
        return interceptor(properties(enabled), rateLimiter);
    }

    private RateLimitInterceptor interceptor(RateLimitProperties properties, CompositeRateLimiter limiter) {
        return new RateLimitInterceptor(limiter, properties, personKeyResolver, objectMapper, clock);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(CLIENT_IP);
        return request;
    }

    /**
     * 등록 요청을 만든다. 실제 운영 경로와 동일하게 {@link RegistrationBodyCachingFilter} 를 태워
     * 본문이 캐시된 요청을 얻는다(인터셉터가 본문을 읽을 수 있는 상태).
     */
    private static HttpServletRequest registrationRequest(String clientIp, String className,
                                                          String studentName, String roomNumber) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/registrations");
        request.setRemoteAddr(clientIp);
        request.setContentType("application/json");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent(("{\"className\":\"" + className
                + "\",\"studentName\":\"" + studentName
                + "\",\"roomNumber\":\"" + roomNumber + "\"}").getBytes(StandardCharsets.UTF_8));

        MockFilterChain chain = new MockFilterChain();
        new RegistrationBodyCachingFilter(properties(true))
                .doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    /**
     * Redis 없이 실제 토큰 버킷 계산을 수행하는 리미터.
     *
     * <p>{@link RedisRateLimiter} 가 항상 실패하도록 해 {@code fail-open=true} 경로를 타게 하면
     * {@link LocalFallbackRateLimiter} 의 진짜 카운팅을 그대로 쓸 수 있다(스코프별 규칙도 그대로 적용된다).
     */
    private static CompositeRateLimiter realCountingLimiter(RateLimitProperties properties) {
        RedisRateLimiter redis = mock(RedisRateLimiter.class);
        when(redis.tryConsume(anyString(), any(), anyLong()))
                .thenThrow(new RateLimitBackendException("테스트: Redis 없음"));
        return new CompositeRateLimiter(redis, new LocalFallbackRateLimiter(properties), properties);
    }

    @Test
    @DisplayName("허용되면 true 를 반환하고 X-RateLimit-* 헤더를 채운다")
    void 허용되면_헤더를_채우고_통과시킨다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.allow(1200L, 1199L, 30_000L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor(true)
                .preHandle(request("GET", "/api/v1/registrations/summary"), response, new Object());

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("1200");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("1199");
        assertThat(response.getHeader("X-RateLimit-Reset")).isNotNull();
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    @DisplayName("차단되면 429 + Retry-After + RATE_LIMITED 본문을 직접 작성하고 false 를 반환한다")
    void 차단되면_429_를_응답한다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.deny(300L, 4_200L, 30_000L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor(true)
                .preHandle(request("POST", "/api/v1/registrations"), response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        // 4200ms → 올림하여 5초
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("300");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentType()).containsIgnoringCase("utf-8");

        String body = response.getContentAsString();
        assertThat(body).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(body).contains("요청이 너무 많습니다");
        assertThat(body).contains("/api/v1/registrations");
    }

    @Test
    @DisplayName("POST /api/v1/registrations 는 global 통과 후 register 스코프를 추가로 소비한다")
    void 등록_요청은_두_스코프를_소비한다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.allow(1200L, 1199L, 30_000L));

        // 본문이 캐시되지 않은 요청이면 개인 버킷은 건너뛴다(= Redis 호출 2회).
        interceptor(true).preHandle(request("POST", "/api/v1/registrations"),
                new MockHttpServletResponse(), new Object());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(2)).tryConsume(keys.capture(), any(), anyLong());
        assertThat(keys.getAllValues()).containsExactly(
                "imlate:rl:global:" + CLIENT_IP,
                "imlate:rl:register:" + CLIENT_IP);
    }

    @Test
    @DisplayName("본문이 있는 등록 요청은 global → register → register-person 순으로 3개 버킷을 소비한다")
    void 등록_요청은_개인_버킷까지_소비한다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.allow(1200L, 1199L, 30_000L));

        interceptor(true).preHandle(registrationRequest(CLIENT_IP, "SKALA1", "김교육", "301"),
                new MockHttpServletResponse(), new Object());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(3)).tryConsume(keys.capture(), any(), anyLong());
        assertThat(keys.getAllValues().get(0)).isEqualTo("imlate:rl:global:" + CLIENT_IP);
        assertThat(keys.getAllValues().get(1)).isEqualTo("imlate:rl:register:" + CLIENT_IP);
        // 개인 키는 HMAC 해시 앞 32자다 — 이름·호수가 Redis 키에 평문으로 들어가지 않는다(SPEC §8.2/§8.3).
        assertThat(keys.getAllValues().get(2)).startsWith("imlate:rl:register:person:");
        assertThat(keys.getAllValues().get(2)).doesNotContain("김교육").doesNotContain("301");
        assertThat(keys.getAllValues().get(2)).hasSize("imlate:rl:register:person:".length() + 32);
    }

    @Test
    @DisplayName("공용 와이파이: 같은 IP 라도 사람이 다르면 200명 전원이 등록에 성공한다")
    void 같은_IP_라도_사람이_다르면_전원_통과한다() throws Exception {
        RateLimitProperties properties = properties(true);
        RateLimitInterceptor interceptor = interceptor(properties, realCountingLimiter(properties));

        int blocked = 0;
        for (int i = 1; i <= 200; i++) {
            HttpServletRequest request = registrationRequest(DORM_WIFI_IP, "SKALA" + (i % 4 + 1),
                    "교육생" + i, String.valueOf(300 + i));
            MockHttpServletResponse response = new MockHttpServletResponse();
            if (!interceptor.preHandle(request, response, new Object())) {
                blocked++;
            }
        }

        // 이번 수정의 핵심: NAT 뒤 200명이 한 IP 를 공유해도 정상 사용자는 한 명도 막히지 않는다.
        assertThat(blocked).isZero();
    }

    @Test
    @DisplayName("같은 사람이 반복 등록하면 개인 버킷(10회/분)에서 차단되고 전용 문구가 나간다")
    void 같은_사람이_반복하면_개인_버킷에서_차단된다() throws Exception {
        RateLimitProperties properties = properties(true);
        RateLimitInterceptor interceptor = interceptor(properties, realCountingLimiter(properties));

        for (int i = 0; i < 10; i++) {
            boolean proceed = interceptor.preHandle(registrationRequest(DORM_WIFI_IP, "SKALA1", "김교육", "301"),
                    new MockHttpServletResponse(), new Object());
            assertThat(proceed).as("%d번째 시도", i + 1).isTrue();
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean proceed = interceptor.preHandle(registrationRequest(DORM_WIFI_IP, "SKALA1", "김교육", "301"),
                response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
        // IP 차단과 구분되는 문구여야 한다.
        assertThat(response.getContentAsString()).contains("같은 정보로 너무 자주");

        // 차단된 사람 옆에서 다른 사람은 계속 등록할 수 있어야 한다.
        assertThat(interceptor.preHandle(registrationRequest(DORM_WIFI_IP, "SKALA1", "이교육", "302"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    @DisplayName("공백·앞뒤 여백만 다른 입력은 같은 사람으로 보고 하나의 개인 버킷을 쓴다")
    void 정규화가_같으면_같은_버킷을_쓴다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.allow(1200L, 1199L, 30_000L));
        RateLimitInterceptor interceptor = interceptor(true);

        interceptor.preHandle(registrationRequest(CLIENT_IP, "SKALA1", "김 교육", "301"),
                new MockHttpServletResponse(), new Object());
        interceptor.preHandle(registrationRequest(CLIENT_IP, " SKALA1 ", "김   교육", " 301"),
                new MockHttpServletResponse(), new Object());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(6)).tryConsume(keys.capture(), any(), anyLong());
        assertThat(keys.getAllValues().get(2)).isEqualTo(keys.getAllValues().get(5));
    }

    @Test
    @DisplayName("/api/v1/lookup 요청은 lookup 스코프를 추가로 소비한다")
    void 조회_요청은_lookup_스코프를_소비한다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.allow(1200L, 1199L, 30_000L));

        interceptor(true).preHandle(request("GET", "/api/v1/lookup"),
                new MockHttpServletResponse(), new Object());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(2)).tryConsume(keys.capture(), any(), anyLong());
        assertThat(keys.getAllValues()).containsExactly(
                "imlate:rl:global:" + CLIENT_IP,
                "imlate:rl:lookup:" + CLIENT_IP);
    }

    @Test
    @DisplayName("일반 GET 요청은 global 스코프만 소비한다(요청당 Redis 호출 최소화)")
    void 일반_요청은_global_만_소비한다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.allow(1200L, 1199L, 30_000L));

        interceptor(true).preHandle(request("GET", "/api/v1/registrations/window"),
                new MockHttpServletResponse(), new Object());

        verify(rateLimiter, times(1)).tryConsume(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("신뢰 프록시 설정이 비어 있으면 X-Forwarded-For 를 무시하고 remoteAddr 를 쓴다")
    void 신뢰_프록시가_없으면_XFF_를_무시한다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.allow(1200L, 1199L, 30_000L));
        MockHttpServletRequest request = request("GET", "/api/v1/registrations/summary");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        interceptor(true).preHandle(request, new MockHttpServletResponse(), new Object());

        // XFF 를 그대로 믿으면 공격자가 헤더만 바꿔 가며 IP 버킷을 무한히 만들 수 있다.
        verify(rateLimiter).tryConsume("imlate:rl:global:" + CLIENT_IP, properties(true).global(), clock.millis());
    }

    @Test
    @DisplayName("신뢰 프록시에서 온 요청만 X-Forwarded-For 를 채택한다")
    void 신뢰_프록시_뒤에서만_XFF_를_쓴다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.allow(1200L, 1199L, 30_000L));
        RateLimitProperties properties = properties(true, List.of(CLIENT_IP + "/32"));
        RateLimitInterceptor interceptor = interceptor(properties, rateLimiter);

        MockHttpServletRequest fromProxy = request("GET", "/api/v1/registrations/summary");
        fromProxy.addHeader("X-Forwarded-For", "203.0.113.7");
        interceptor.preHandle(fromProxy, new MockHttpServletResponse(), new Object());

        verify(rateLimiter).tryConsume("imlate:rl:global:203.0.113.7", properties.global(), clock.millis());
    }

    @Test
    @DisplayName("rate limit 이 비활성이면 리미터를 호출하지 않고 통과시킨다")
    void 비활성이면_아무것도_하지_않는다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor(false)
                .preHandle(request("POST", "/api/v1/registrations"), response, new Object());

        assertThat(proceed).isTrue();
        assertThat(response.getHeader("X-RateLimit-Limit")).isNull();
        verifyNoInteractions(rateLimiter);
    }

    @Test
    @DisplayName("CORS preflight(OPTIONS)와 actuator 요청은 계산 대상에서 제외한다")
    void 프리플라이트와_actuator_는_제외된다() throws Exception {
        RateLimitInterceptor interceptor = interceptor(true);

        assertThat(interceptor.preHandle(request("OPTIONS", "/api/v1/registrations"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("GET", "/actuator/health"),
                new MockHttpServletResponse(), new Object())).isTrue();

        verifyNoInteractions(rateLimiter);
    }

    @Test
    @DisplayName("리미터가 예외를 던져도 요청은 통과시킨다(가용성 우선)")
    void 리미터_예외는_요청을_막지_않는다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenThrow(new IllegalStateException("backend down"));

        boolean proceed = interceptor(true).preHandle(request("GET", "/api/v1/registrations/summary"),
                new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
    }

    @Test
    @DisplayName("본문이 깨져 개인 키를 못 만들어도 등록 요청은 막히지 않는다")
    void 본문_파싱_실패는_요청을_막지_않는다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.allow(1200L, 1199L, 30_000L));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/registrations");
        request.setRemoteAddr(CLIENT_IP);
        request.setContentType("application/json");
        request.setContent("{ 깨진 JSON".getBytes(StandardCharsets.UTF_8));
        MockFilterChain chain = new MockFilterChain();
        new RegistrationBodyCachingFilter(properties(true))
                .doFilter(request, new MockHttpServletResponse(), chain);

        boolean proceed = interceptor(true)
                .preHandle((HttpServletRequest) chain.getRequest(), new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
        // 개인 버킷은 건너뛰고 IP 버킷 2개만 소비한다.
        verify(rateLimiter, times(2)).tryConsume(anyString(), any(), anyLong());
    }
}
