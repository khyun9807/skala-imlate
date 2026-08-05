package com.skala.imlate.notification.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.skala.imlate.common.properties.EmailProperties;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;

/**
 * 발송 하트비트 지표 발행용 CloudWatch 클라이언트.
 *
 * <p>{@code imlate.notification.heartbeat.publisher = cloudwatch} 일 때만 만든다.
 * 로컬·테스트 프로파일은 {@code none} 이라 이 빈이 생기지 않으므로 <b>AWS 자격증명 없이도 기동된다.</b>
 *
 * <p>리전은 SES 설정({@code imlate.email.ses.region})을 재사용한다. 같은 계정·같은 리전에
 * 배포되는 단일 스택이라 리전을 두 벌로 관리할 이유가 없고, 갈라 두면 한쪽만 바뀌어
 * 지표가 엉뚱한 리전에 쌓이는 사고가 난다.
 *
 * <p>인스턴스 역할에 {@code CloudWatchAgentServerPolicy} 가 붙어 있어
 * {@code cloudwatch:PutMetricData} 권한은 이미 있다(IAM 변경 불필요).
 */
@Configuration
@ConditionalOnProperty(prefix = "imlate.notification.heartbeat", name = "publisher",
        havingValue = "cloudwatch")
public class CloudWatchClientConfig {

    private static final Logger log = LoggerFactory.getLogger(CloudWatchClientConfig.class);

    /** 기본 리전. SES 설정이 비어 있을 때만 쓰인다. */
    private static final String DEFAULT_REGION = "ap-northeast-2";

    /**
     * @param emailProperties SES 설정(리전 재사용)
     * @return CloudWatch 클라이언트
     */
    @Bean(destroyMethod = "close")
    public CloudWatchClient cloudWatchClient(EmailProperties emailProperties) {
        String region = emailProperties.ses().region();
        if (region == null || region.isBlank()) {
            region = DEFAULT_REGION;
        }
        log.info("CloudWatch 클라이언트를 생성합니다(발송 하트비트 지표용). region={}", region);
        return CloudWatchClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
