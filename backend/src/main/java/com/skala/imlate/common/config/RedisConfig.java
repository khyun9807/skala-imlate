package com.skala.imlate.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Redis 설정. WAL·rate limit·통계 모듈이 공통으로 사용하는 문자열 템플릿과 전용 ObjectMapper 를 제공한다.
 *
 * <p>제네릭 직렬화기를 쓰지 않고 {@link StringRedisTemplate} + JSON 문자열을 직접 다뤄
 * 라이브러리 버전 변경에 영향을 받지 않도록 한다.
 */
@Configuration
public class RedisConfig {

    /** 문자열 기반 Redis 템플릿. */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Redis 값 직렬화 전용 ObjectMapper.
     *
     * <p>웹 응답용 ObjectMapper 와 충돌하지 않도록 별도 이름을 쓰고 {@code @Primary} 는 붙이지 않는다.
     */
    @Bean("imlateObjectMapper")
    public ObjectMapper imlateObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // 날짜/시간은 숫자 타임스탬프가 아니라 ISO-8601 문자열로 저장한다.
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 저장된 JSON 에 새 필드가 생겨도 역직렬화가 깨지지 않도록 한다.
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }
}
