package com.skala.imlate.notification.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.skala.imlate.common.properties.SmsProperties;

/**
 * Aligo 문자 API 전용 {@link RestClient} 설정.
 *
 * <p>발송 지연이 스케줄러 스레드를 오래 붙잡지 않도록 연결·응답 타임아웃을 반드시 지정한다.
 * {@code imlate.sms.provider=aligo} 일 때만 빈을 만든다.
 */
@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    /** Aligo 전용 RestClient. {@code @Qualifier("aligoRestClient")} 로 주입한다. */
    @Bean("aligoRestClient")
    @ConditionalOnProperty(prefix = "imlate.sms", name = "provider", havingValue = "aligo")
    public RestClient aligoRestClient(SmsProperties properties) {
        SmsProperties.Aligo config = properties.aligo();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(config.connectTimeoutMs());
        requestFactory.setReadTimeout(config.readTimeoutMs());
        log.info("Aligo RestClient 를 생성합니다. url={}, connectTimeoutMs={}, readTimeoutMs={}, testMode={}",
                config.apiUrl(), config.connectTimeoutMs(), config.readTimeoutMs(), config.testMode());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
