package com.skala.imlate.ratelimit;

import java.io.IOException;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import com.skala.imlate.common.properties.RateLimitProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 등록 요청(POST /api/v1/registrations)의 본문만 골라 재읽기 가능하게 감싸는 필터.
 *
 * <p><b>왜 필요한가</b> — 개인 단위 rate limit 의 키는 요청 <u>본문</u>의 {@code 반|이름|호수} 에서 나온다.
 * 그런데 서블릿 본문은 한 번만 읽을 수 있어, 인터셉터가 그냥 읽으면 컨트롤러가 빈 본문을 받는다.
 * 그래서 디스패처보다 앞선 필터에서 본문을 담아 두고({@link CachedBodyHttpServletRequest})
 * 인터셉터와 컨트롤러가 각각 읽게 한다.
 *
 * <p><b>왜 인터셉터 방식(a)을 골랐나</b> — 대안은 개인 제한을 {@code RegistrationService} 진입점에서
 * 수행하는 것(b)이었다. (b) 는 본문 파싱 문제가 없지만, 차단을 예외로 알릴 수밖에 없어
 * {@code Retry-After} · {@code X-RateLimit-*} 헤더를 붙일 수 없다. 그러면 IP 차단과 개인 차단의
 * 응답 계약(SPEC §8: 429 + code=RATE_LIMITED + Retry-After)이 서로 달라진다.
 * 또한 rate limit 이 등록 도메인 코드로 새어 들어가 책임 경계도 흐려진다.
 * 본문 캐싱은 아래처럼 대상과 크기를 좁히면 비용이 사실상 없으므로 (a) 를 택했다.
 *
 * <p><b>안전장치</b> — 다음 조건을 모두 만족할 때만 감싼다. 하나라도 어긋나면 원본 요청을 그대로 흘려보내고
 * 개인 버킷 검사는 건너뛴다(리미터 때문에 정상 등록이 실패하는 일은 없어야 한다).
 * <ul>
 *   <li>rate limit 활성 + {@code POST /api/v1/registrations}</li>
 *   <li>{@code Content-Type} 이 JSON</li>
 *   <li>{@code Content-Length} 가 1 이상 {@value #MAX_CACHED_BODY_BYTES} 바이트 이하
 *       — 길이를 모르는(chunked) 요청은 <b>스트림을 건드리지 않는다</b>.
 *       읽다가 상한을 넘겨 중단하면 이미 소비된 본문을 컨트롤러가 못 읽기 때문이다.</li>
 * </ul>
 */
public class RegistrationBodyCachingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RegistrationBodyCachingFilter.class);

    /** 캐싱을 허용할 본문 최대 크기(8KiB). 등록 본문은 200바이트 남짓이라 충분히 여유롭다. */
    static final int MAX_CACHED_BODY_BYTES = 8 * 1024;

    private static final String METHOD_POST = "POST";
    private static final String PATH_REGISTRATIONS = "/api/v1/registrations";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final RateLimitProperties properties;

    public RegistrationBodyCachingFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!shouldCache(request)) {
            chain.doFilter(request, response);
            return;
        }

        byte[] body;
        try {
            body = request.getInputStream().readNBytes((int) request.getContentLengthLong());
        } catch (IOException e) {
            // 본문을 못 읽으면 캐싱만 포기한다. 이 시점에 스트림이 이미 소비됐을 수 있으므로
            // 원본 요청을 그대로 넘겨 기존과 동일한 오류 처리를 타게 한다.
            log.debug("등록 요청 본문 캐싱 실패 — 개인 rate limit 을 건너뛴다.", e);
            chain.doFilter(request, response);
            return;
        }

        chain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    /** 감쌀 대상인지 판단한다(가장 싼 검사부터 확인해 일반 요청에 비용을 주지 않는다). */
    private boolean shouldCache(HttpServletRequest request) {
        if (!properties.enabled() || !METHOD_POST.equals(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null || !uri.endsWith(PATH_REGISTRATIONS)) {
            return false;
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(CONTENT_TYPE_JSON)) {
            return false;
        }
        long length = request.getContentLengthLong();
        return length > 0 && length <= MAX_CACHED_BODY_BYTES;
    }
}
