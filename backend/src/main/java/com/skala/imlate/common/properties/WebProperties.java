package com.skala.imlate.common.properties;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 웹 계층 설정(`imlate.web.*`). 현재는 CORS 허용 오리진만 사용한다.
 *
 * @param allowedOrigins CORS 허용 오리진 목록(기본 http://localhost:5173)
 */
@ConfigurationProperties(prefix = "imlate.web")
public record WebProperties(List<String> allowedOrigins) {

    public WebProperties {
        // 미설정이면 로컬 개발 오리진을 기본으로 둔다. 빈 문자열 항목은 제거.
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            allowedOrigins = List.of("http://localhost:5173");
        } else {
            allowedOrigins = allowedOrigins.stream()
                    .filter(origin -> origin != null && !origin.isBlank())
                    .map(String::trim)
                    .toList();
        }
    }
}
