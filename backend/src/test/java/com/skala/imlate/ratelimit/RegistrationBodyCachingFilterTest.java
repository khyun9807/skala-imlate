package com.skala.imlate.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.skala.imlate.common.properties.RateLimitProperties;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;

/**
 * {@link RegistrationBodyCachingFilter} 단위 테스트.
 *
 * <p>이 필터의 존재 이유는 단 하나 — 인터셉터가 본문을 읽어도 <b>컨트롤러가 같은 본문을 다시 읽을 수 있어야</b>
 * 한다는 것이다. 이게 깨지면 등록 자체가 실패하므로 가장 중요한 검증이다.
 */
@DisplayName("등록 본문 캐싱 필터(RegistrationBodyCachingFilter)")
class RegistrationBodyCachingFilterTest {

    private static final String BODY = "{\"className\":\"SKALA1\",\"studentName\":\"김교육\",\"roomNumber\":\"301\"}";

    private static RateLimitProperties properties(boolean enabled) {
        return new RateLimitProperties(enabled, true,
                new RateLimitProperties.Rule(1200, 1200, 60),
                new RateLimitProperties.Rule(300, 300, 60),
                new RateLimitProperties.Rule(10, 10, 60),
                new RateLimitProperties.Rule(120, 120, 60),
                List.of(), 1200);
    }

    private static MockHttpServletRequest registrationRequest(String contentType, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/registrations");
        request.setRemoteAddr("203.0.113.50");
        if (contentType != null) {
            request.setContentType(contentType);
        }
        if (body != null) {
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    private static ServletRequest passThrough(RateLimitProperties properties, MockHttpServletRequest request)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new RegistrationBodyCachingFilter(properties).doFilter(request, new MockHttpServletResponse(), chain);
        return chain.getRequest();
    }

    @Test
    @DisplayName("등록 요청 본문을 캐시해 두 번 이상 읽을 수 있게 만든다")
    void 본문을_여러_번_읽을_수_있다() throws Exception {
        HttpServletRequest wrapped = (HttpServletRequest) passThrough(properties(true),
                registrationRequest("application/json", BODY));

        // 1회차: 인터셉터(개인 키 추출)가 읽는다.
        String first = new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // 2회차: 컨트롤러(@RequestBody)가 읽는다. 여기서 빈 값이 나오면 등록이 통째로 깨진다.
        String second = new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String viaReader = wrapped.getReader().readLine();

        assertThat(first).isEqualTo(BODY);
        assertThat(second).isEqualTo(BODY);
        assertThat(viaReader).isEqualTo(BODY);
        assertThat(wrapped.getContentLengthLong()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("캐시된 본문은 요청 속성으로도 꺼낼 수 있다(래핑이 중첩돼도 접근 가능)")
    void 요청_속성으로_본문을_꺼낼_수_있다() throws Exception {
        ServletRequest wrapped = passThrough(properties(true), registrationRequest("application/json", BODY));

        assertThat(CachedBodyHttpServletRequest.cachedBody(wrapped))
                .isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("rate limit 이 꺼져 있으면 본문을 건드리지 않는다")
    void 비활성이면_감싸지_않는다() throws Exception {
        ServletRequest passed = passThrough(properties(false), registrationRequest("application/json", BODY));

        assertThat(passed).isNotInstanceOf(CachedBodyHttpServletRequest.class);
        assertThat(CachedBodyHttpServletRequest.cachedBody(passed)).isNull();
    }

    @Test
    @DisplayName("JSON 이 아니거나 본문이 비었으면 감싸지 않는다")
    void 대상이_아니면_감싸지_않는다() throws Exception {
        assertThat(CachedBodyHttpServletRequest.cachedBody(
                passThrough(properties(true), registrationRequest("application/x-www-form-urlencoded", "a=b"))))
                .isNull();
        assertThat(CachedBodyHttpServletRequest.cachedBody(
                passThrough(properties(true), registrationRequest(null, BODY))))
                .isNull();
        assertThat(CachedBodyHttpServletRequest.cachedBody(
                passThrough(properties(true), registrationRequest("application/json", ""))))
                .isNull();
    }

    @Test
    @DisplayName("GET 이나 다른 경로는 감싸지 않는다(등록 경로에만 비용을 쓴다)")
    void 등록_경로가_아니면_감싸지_않는다() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/registrations");
        get.setContentType("application/json");
        MockHttpServletRequest otherPath = new MockHttpServletRequest("POST", "/api/v1/lookup");
        otherPath.setContentType("application/json");
        otherPath.setContent(BODY.getBytes(StandardCharsets.UTF_8));

        assertThat(CachedBodyHttpServletRequest.cachedBody(passThrough(properties(true), get))).isNull();
        assertThat(CachedBodyHttpServletRequest.cachedBody(passThrough(properties(true), otherPath))).isNull();
    }

    @Test
    @DisplayName("본문이 상한(8KiB)을 넘으면 캐싱을 포기하고 원본을 그대로 흘려보낸다")
    void 너무_큰_본문은_캐싱하지_않는다() throws Exception {
        String huge = "{\"className\":\"" + "가".repeat(RegistrationBodyCachingFilter.MAX_CACHED_BODY_BYTES)
                + "\",\"studentName\":\"김교육\",\"roomNumber\":\"301\"}";
        ServletRequest passed = passThrough(properties(true), registrationRequest("application/json", huge));

        // 스트림을 건드리지 않았으므로 컨트롤러는 평소대로 본문을 읽어 400(검증 실패)을 받는다.
        assertThat(CachedBodyHttpServletRequest.cachedBody(passed)).isNull();
        assertThat(new String(passed.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(huge);
    }
}
