package com.skala.imlate.common.config;

import java.time.Clock;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.skala.imlate.common.properties.ImlateProperties;

/**
 * 서비스 기준 시계 설정. 모든 시각 계산은 여기서 만든 {@link Clock} 을 주입받아 사용한다.
 */
@Configuration
public class ClockConfig {

    private static final Logger log = LoggerFactory.getLogger(ClockConfig.class);

    /** 서비스 기준 시간대(기본 Asia/Seoul). */
    @Bean
    public ZoneId serviceZoneId(ImlateProperties properties) {
        String timezone = properties.timezone();
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException ex) {
            log.warn("Invalid imlate.timezone='{}' — falling back to {}", timezone, ImlateProperties.DEFAULT_TIMEZONE);
            return ZoneId.of(ImlateProperties.DEFAULT_TIMEZONE);
        }
    }

    /** 서비스 기준 시계. {@code LocalDate.now()} 대신 반드시 이 빈을 사용한다. */
    @Bean
    public Clock clock(ImlateProperties properties) {
        ZoneId zoneId = serviceZoneId(properties);
        log.info("Service clock initialized with zone={}", zoneId);
        return Clock.system(zoneId);
    }
}
