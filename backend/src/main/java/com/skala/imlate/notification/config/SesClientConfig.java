package com.skala.imlate.notification.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.skala.imlate.common.properties.EmailProperties;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * Amazon SES v2 클라이언트 설정.
 *
 * <p>{@code imlate.email.provider=ses} 일 때만 빈을 만든다. 기본값(noop)에서는 이 설정이 아예
 * 적용되지 않으므로 <b>AWS 자격증명이 없어도 애플리케이션이 기동된다.</b>
 * 자격증명은 {@link DefaultCredentialsProvider} 체인(EC2 인스턴스 프로파일 → 환경변수 → 프로파일)으로 해결한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "imlate.email", name = "provider", havingValue = "ses")
public class SesClientConfig {

    private static final Logger log = LoggerFactory.getLogger(SesClientConfig.class);

    /** SES v2 클라이언트. 애플리케이션 종료 시 close 된다. */
    @Bean(destroyMethod = "close")
    public SesV2Client sesV2Client(EmailProperties properties) {
        String region = properties.ses().region();
        log.info("SES v2 클라이언트를 생성합니다. region={}, from={}", region, properties.ses().from());
        return SesV2Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
