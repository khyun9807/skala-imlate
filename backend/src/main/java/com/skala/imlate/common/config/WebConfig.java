package com.skala.imlate.common.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.skala.imlate.common.properties.WebProperties;

/**
 * 웹 공통 설정. CORS 만 담당하며, 인터셉터 등록은 각 모듈이 자기 {@link WebMvcConfigurer} 로 추가한다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);
    private static final String DEFAULT_ORIGIN = "http://localhost:5173";

    private final WebProperties webProperties;

    public WebConfig(WebProperties webProperties) {
        this.webProperties = webProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = webProperties.allowedOrigins();
        if (origins.isEmpty()) {
            // 설정이 모두 비어 있으면 로컬 개발 오리진만 허용한다.
            origins = List.of(DEFAULT_ORIGIN);
        }
        log.info("CORS allowed origins for /api/** = {}", origins);
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
