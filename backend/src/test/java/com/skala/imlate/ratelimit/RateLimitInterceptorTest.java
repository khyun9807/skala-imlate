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

import java.time.Clock;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skala.imlate.common.properties.RateLimitProperties;
import com.skala.imlate.support.TestFixtures;

/**
 * {@link RateLimitInterceptor} 단위 테스트.
 *
 * <p>리미터는 목으로 대체하고, 인터셉터가 만들어 내는 <b>HTTP 응답(429 · 헤더 · 본문)</b>과
 * 스코프 결정 규칙만 검증한다. 인터셉터는 어떤 경우에도 예외를 밖으로 던지지 않아야 한다.
 */
@DisplayName("Rate limit 인터셉터(RateLimitInterceptor)")
class RateLimitInterceptorTest {

    private static final String CLIENT_IP = "9.9.9.9";

    private CompositeRateLimiter rateLimiter;
    private ObjectMapper objectMapper;
    private Clock clock;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(CompositeRateLimiter.class);
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        clock = TestFixtures.clockAt(LocalTime.of(21, 0));
    }

    private static RateLimitProperties properties(boolean enabled) {
        return new RateLimitProperties(enabled, true,
                new RateLimitProperties.Rule(120, 120, 60),
                new RateLimitProperties.Rule(8, 8, 60),
                new RateLimitProperties.Rule(40, 40, 60),
                List.of(), 120);
    }

    private RateLimitInterceptor interceptor(boolean enabled) {
        return new RateLimitInterceptor(rateLimiter, properties(enabled), objectMapper, clock);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(CLIENT_IP);
        return request;
    }

    @Test
    @DisplayName("허용되면 true 를 반환하고 X-RateLimit-* 헤더를 채운다")
    void 허용되면_헤더를_채우고_통과시킨다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.allow(120L, 119L, 30_000L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor(true)
                .preHandle(request("GET", "/api/v1/registrations/summary"), response, new Object());

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("120");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("119");
        assertThat(response.getHeader("X-RateLimit-Reset")).isNotNull();
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    @DisplayName("차단되면 429 + Retry-After + RATE_LIMITED 본문을 직접 작성하고 false 를 반환한다")
    void 차단되면_429_를_응답한다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.deny(8L, 4_200L, 30_000L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor(true)
                .preHandle(request("POST", "/api/v1/registrations"), response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        // 4200ms → 올림하여 5초
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("8");
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
                .thenReturn(RateLimitDecision.allow(120L, 119L, 30_000L));

        interceptor(true).preHandle(request("POST", "/api/v1/registrations"),
                new MockHttpServletResponse(), new Object());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(2)).tryConsume(keys.capture(), any(), anyLong());
        assertThat(keys.getAllValues()).containsExactly(
                "imlate:rl:global:" + CLIENT_IP,
                "imlate:rl:register:" + CLIENT_IP);
    }

    @Test
    @DisplayName("/api/v1/lookup 요청은 lookup 스코프를 추가로 소비한다")
    void 조회_요청은_lookup_스코프를_소비한다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.allow(120L, 119L, 30_000L));

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
                .thenReturn(RateLimitDecision.allow(120L, 119L, 30_000L));

        interceptor(true).preHandle(request("GET", "/api/v1/registrations/window"),
                new MockHttpServletResponse(), new Object());

        verify(rateLimiter, times(1)).tryConsume(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("X-Forwarded-For 가 있으면 첫 IP 를 버킷 키로 사용한다")
    void 프록시_뒤에서는_XFF_첫_IP_를_쓴다() throws Exception {
        when(rateLimiter.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(RateLimitDecision.allow(120L, 119L, 30_000L));
        MockHttpServletRequest request = request("GET", "/api/v1/registrations/summary");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        interceptor(true).preHandle(request, new MockHttpServletResponse(), new Object());

        verify(rateLimiter).tryConsume("imlate:rl:global:203.0.113.7", properties(true).global(), clock.millis());
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
}
