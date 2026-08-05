package com.skala.imlate.common.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 웹 응답용 Jackson 설정. 날짜·시간을 ISO-8601 문자열로 직렬화한다.
 */
@Configuration
public class JacksonConfig {

    /** Spring MVC 가 사용하는 ObjectMapper 빌더를 커스터마이즈한다. */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer imlateJacksonCustomizer() {
        return builder -> {
            builder.modulesToInstall(new JavaTimeModule());
            // "2026-08-05T22:10:00+09:00" 처럼 사람이 읽을 수 있는 형태로 응답한다.
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                    SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        };
    }
}
