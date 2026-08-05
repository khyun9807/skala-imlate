package com.skala.imlate.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.skala.imlate.common.properties.RateLimitProperties;

/**
 * Rate limit 인터셉터·필터 등록. 공통 {@code WebConfig} 는 CORS 만 담당하므로 모듈이 직접 등록한다.
 *
 * <p>인터셉터 order 를 0 으로 두어 통계 인터셉터보다 먼저 실행시킨다(차단된 요청은 통계에 잡히지 않게).
 */
@Configuration
public class RateLimitWebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RateLimitWebConfig.class);

    /** 다른 모듈 인터셉터보다 먼저 실행되도록 하는 순서 값. */
    public static final int INTERCEPTOR_ORDER = 0;

    /**
     * 본문 캐싱 필터 순서. 인코딩·요청컨텍스트 등 스프링 기본 필터(음수 order) 뒤,
     * 디스패처 서블릿 앞에서 돌면 되므로 뒤쪽에 둔다.
     */
    public static final int BODY_CACHING_FILTER_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    /** 본문 캐싱 필터를 적용할 경로(등록 API 하나뿐이다). */
    private static final String REGISTRATION_PATH = "/api/v1/registrations";

    private final RateLimitInterceptor rateLimitInterceptor;
    private final RateLimitProperties properties;

    public RateLimitWebConfig(RateLimitInterceptor rateLimitInterceptor, RateLimitProperties properties) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("rate limit 인터셉터 등록: enabled={}, failOpen={}, "
                        + "global={}/{}s(IP), register={}/{}s(IP), register-person={}/{}s(개인), lookup={}/{}s(IP), "
                        + "신뢰 프록시 {}개(0개면 X-Forwarded-For 무시)",
                properties.enabled(), properties.failOpen(),
                properties.global().capacity(), properties.global().refillPeriodSeconds(),
                properties.register().capacity(), properties.register().refillPeriodSeconds(),
                properties.registerPerson().capacity(), properties.registerPerson().refillPeriodSeconds(),
                properties.lookup().capacity(), properties.lookup().refillPeriodSeconds(),
                rateLimitInterceptor.trustedProxyCount());

        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/actuator/**", "/error")
                .order(INTERCEPTOR_ORDER);
    }

    /**
     * 등록 요청 본문을 재읽기 가능하게 감싸는 필터.
     *
     * <p>등록 경로에만 매핑한다 — 다른 API 는 본문을 복사하지 않으므로 추가 비용이 0 이다.
     * ({@code @Component} 로 두면 스프링 부트가 모든 경로에 자동 등록하므로 일부러 여기서만 등록한다.)
     *
     * @return 필터 등록 정보
     */
    @Bean
    public FilterRegistrationBean<RegistrationBodyCachingFilter> registrationBodyCachingFilter() {
        FilterRegistrationBean<RegistrationBodyCachingFilter> registration =
                new FilterRegistrationBean<>(new RegistrationBodyCachingFilter(properties));
        registration.addUrlPatterns(REGISTRATION_PATH);
        registration.setOrder(BODY_CACHING_FILTER_ORDER);
        registration.setName("registrationBodyCachingFilter");
        return registration;
    }
}
