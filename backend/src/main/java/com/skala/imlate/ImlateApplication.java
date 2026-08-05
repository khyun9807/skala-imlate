package com.skala.imlate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 기숙사 야간 복귀(23:30) 등록 시스템 진입점.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ImlateApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImlateApplication.class, args);
    }
}
