package com.skala.imlate.notification.ops;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.NotificationProperties;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

/**
 * 발송 완료 하트비트를 CloudWatch 지표로 직접 올린다(PutMetricData).
 *
 * <p><b>왜 이 방식인가</b> — 대안이던 "로그 마커 + 지표 필터"는 두 가지 문제가 있다.
 * <ul>
 *   <li>로그 그룹 {@code /imlate/app} 은 CloudWatch 에이전트가 만든다. terraform 이 소유하지 않으므로
 *       지표 필터를 붙이려면 존재 여부에 의존하게 되고, 배포 순서에 따라 apply 가 깨진다.</li>
 *   <li>평문 로그에서는 <b>차원(Dimension)을 만들 수 없다.</b> 환경이 늘어나면 지표를 구분할 방법이 없다.</li>
 * </ul>
 * 인스턴스 역할에 이미 {@code CloudWatchAgentServerPolicy} 가 붙어 있어 {@code cloudwatch:PutMetricData}
 * 권한이 있으므로 IAM 변경도 필요 없다.
 *
 * <p><b>이 지표가 하는 일</b> — {@code Imlate/DispatchCompleted} 가 하루 한 번 올라오지 않으면
 * (= 21:50 에 아무 일도 일어나지 않았으면) 알람이 울린다. 인스턴스가 죽어 발송 자체가 실행되지
 * 못한 상황을 밖에서 감지하는 유일한 수단이다. 이 도메인에서 발송 실패는 교육생이 기숙사에
 * 못 들어가는 것을 뜻하므로, 조용한 실패를 남겨 두면 안 된다.
 *
 * <p>지표 전송 실패는 <b>절대 발송 흐름을 깨뜨리지 않는다.</b> 모든 예외를 삼키고 로그만 남긴다.
 */
@Component
@ConditionalOnProperty(prefix = "imlate.notification.heartbeat", name = "publisher",
        havingValue = "cloudwatch")
public class CloudWatchDispatchHeartbeatPublisher implements DispatchHeartbeatPublisher {

    private static final Logger log = LoggerFactory.getLogger(CloudWatchDispatchHeartbeatPublisher.class);

    /** 알람이 감시하는 지표 이름. terraform modules/monitoring 과 반드시 일치해야 한다. */
    public static final String METRIC_COMPLETED = "DispatchCompleted";
    public static final String METRIC_FAILURES = "DispatchFailures";

    private static final String DIMENSION_ENVIRONMENT = "Environment";
    private static final String DIMENSION_KIND = "Kind";

    private final CloudWatchClient cloudWatch;
    private final String namespace;
    private final String environment;

    /**
     * @param cloudWatch CloudWatch 클라이언트
     * @param properties {@code imlate.notification.*} 설정(네임스페이스·환경 이름)
     */
    public CloudWatchDispatchHeartbeatPublisher(CloudWatchClient cloudWatch, NotificationProperties properties) {
        this.cloudWatch = cloudWatch;
        this.namespace = properties.heartbeat().namespace();
        this.environment = properties.heartbeat().environment();
    }

    @Override
    public void publish(DispatchHeartbeat heartbeat) {
        if (heartbeat == null) {
            return;
        }
        try {
            List<Dimension> dimensions = List.of(
                    Dimension.builder().name(DIMENSION_ENVIRONMENT).value(environment).build(),
                    Dimension.builder().name(DIMENSION_KIND).value(heartbeat.kind().name()).build());

            Instant now = Instant.now();
            List<MetricDatum> data = new ArrayList<>(2);
            data.add(MetricDatum.builder()
                    .metricName(METRIC_COMPLETED)
                    .value(1.0d)
                    .unit(StandardUnit.COUNT)
                    .timestamp(now)
                    .dimensions(dimensions)
                    .build());
            // 실패가 0 이어도 반드시 올린다. 값이 아예 없으면 알람이 "데이터 없음" 과 구분하지 못한다.
            data.add(MetricDatum.builder()
                    .metricName(METRIC_FAILURES)
                    .value((double) heartbeat.failureCount())
                    .unit(StandardUnit.COUNT)
                    .timestamp(now)
                    .dimensions(dimensions)
                    .build());

            cloudWatch.putMetricData(PutMetricDataRequest.builder()
                    .namespace(namespace)
                    .metricData(data)
                    .build());

            log.info("하트비트 지표 발행: ns={}, kind={}, date={}, result={}, 인원={}, 실패={}",
                    namespace, heartbeat.kind(), heartbeat.date(), heartbeat.result(),
                    heartbeat.targetCount(), heartbeat.failureCount());
        } catch (RuntimeException ex) {
            // 지표를 못 올렸다고 발송이 실패로 바뀌면 안 된다. 다만 알람이 오탐할 수 있으므로 WARN 으로 남긴다.
            log.warn("하트비트 지표 발행 실패 — 발송 자체에는 영향 없음. kind={}, cause={}",
                    heartbeat.kind(), ex.toString());
        }
    }
}
