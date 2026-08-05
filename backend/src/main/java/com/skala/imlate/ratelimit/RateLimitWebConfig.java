package com.skala.imlate.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.skala.imlate.common.properties.RateLimitProperties;

/**
 * Rate limit 인터셉터 등록. 공통 {@code WebConfig} 는 CORS 만 담당하므로 모듈이 직접 등록한다.
 *
 * <p>order 를 0 으로 두어 통계 인터셉터보다 먼저 실행시킨다(차단된 요청은 통계에 잡히지 않게).
 */
@Configuration
public class RateLimitWebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RateLimitWebConfig.class);

    /** 다른 모듈 인터셉터보다 먼저 실행되도록 하는 순서 값. */
    public static final int INTERCEPTOR_ORDER = 0;

    private final RateLimitInterceptor rateLimitInterceptor;
    private final RateLimitProperties properties;

    public RateLimitWebConfig(RateLimitInterceptor rateLimitInterceptor, RateLimitProperties properties) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("rate limit 인터셉터 등록: enabled={}, failOpen={}, global={}/{}s, register={}/{}s, lookup={}/{}s",
                properties.enabled(), properties.failOpen(),
                properties.global().capacity(), properties.global().refillPeriodSeconds(),
                properties.register().capacity(), properties.register().refillPeriodSeconds(),
                properties.lookup().capacity(), properties.lookup().refillPeriodSeconds());

        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/actuator/**", "/error")
                .order(INTERCEPTOR_ORDER);
    }
}
