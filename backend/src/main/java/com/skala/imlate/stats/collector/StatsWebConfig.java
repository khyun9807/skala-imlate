package com.skala.imlate.stats.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.skala.imlate.common.properties.StatsProperties;

/**
 * 통계 인터셉터 등록(SPEC §4.5 — 인터셉터는 각 모듈이 자기 {@link WebMvcConfigurer} 로 추가한다).
 *
 * <p>순서는 rate limit 인터셉터보다 뒤(order=200)로 둔다. 차단된 요청은 방문으로 집계하지 않기 위함이다.
 */
@Configuration
public class StatsWebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(StatsWebConfig.class);

    /** rate limit 이후에 동작하도록 충분히 큰 순서 값을 쓴다. */
    private static final int INTERCEPTOR_ORDER = 200;

    private final StatsInterceptor statsInterceptor;
    private final StatsProperties statsProperties;

    public StatsWebConfig(StatsInterceptor statsInterceptor, StatsProperties statsProperties) {
        this.statsInterceptor = statsInterceptor;
        this.statsProperties = statsProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!statsProperties.enabled()) {
            log.info("통계 인터셉터를 등록하지 않습니다(imlate.stats.enabled=false).");
            return;
        }
        registry.addInterceptor(statsInterceptor)
                .addPathPatterns("/api/**")
                // 통계 조회 API 와 actuator 는 집계 대상이 아니다.
                .excludePathPatterns("/api/v1/stats/**", "/actuator/**")
                .order(INTERCEPTOR_ORDER);
        log.info("통계 인터셉터를 /api/** 에 등록했습니다.");
    }
}
