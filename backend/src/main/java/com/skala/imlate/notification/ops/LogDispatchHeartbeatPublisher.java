package com.skala.imlate.notification.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.NotificationProperties;

/**
 * 하트비트를 <b>정해진 형식의 로그 한 줄</b>로 남기는 발행기(기본 구현).
 *
 * <p><b>왜 AWS SDK 로 PutMetricData 를 부르지 않는가</b><br>
 * {@code software.amazon.awssdk:cloudwatch} 가 현재 의존성에 없다(있는 것은 {@code sesv2}, {@code sts} 뿐이다).
 * build.gradle 은 이 작업의 소유 파일이 아니므로 임의로 의존성을 추가하지 않는다. 대신 운영 EC2 에
 * 이미 CloudWatch Agent 가 설치되어 애플리케이션 stdout({@code imlate.log})을 로그 그룹
 * {@code /imlate/app} 으로 보내고 있으므로, <b>로그 지표 필터(metric filter)</b> 로 동일한 지표를
 * 만들 수 있다. 즉 의존성 추가 없이 "21:50 에 아무 일도 없었다"를 밖에서 감지할 수 있다.
 *
 * <p><b>인프라 쪽 연결(로그 그룹 {@code /imlate/app} 의 지표 필터)</b>
 * <pre>
 *   Imlate/DispatchCompleted : 필터 패턴 "IMLATE_HEARTBEAT kind=DISPATCH"  → metric value 1
 *   Imlate/DispatchFailures  : 필터 패턴 "IMLATE_DISPATCH_FAILURE"          → metric value 1 (SUM = 실패 건수)
 * </pre>
 * 두 패턴 모두 따옴표로 감싼 <b>정확한 부분 문자열</b> 일치라 로그 앞머리(타임스탬프·레벨·스레드명)에
 * 영향을 받지 않는다. 위치 기반 패턴({@code [f1, f2, ...]})은 줄 첫 토큰부터 세기 때문에
 * 로그 프리픽스와 어긋난다 — 쓰면 안 된다.
 *
 * <p>그래서 {@code kind=...} 를 표식 바로 뒤에 붙인다. 위 패턴이 한 덩어리로 일치해야 하므로
 * <b>필드 순서를 바꾸면 알람이 조용히 죽는다</b>(테스트가 순서를 고정하고 있다).
 *
 * <p>SDK 의존성이 추가되면 이 클래스를 교체(같은 인터페이스 구현 추가)하는 것만으로 실제
 * PutMetricData 로 바꿀 수 있다. 지표 이름·차원은 그때도 그대로 쓰도록 이름에 박아 둔다.
 */
@Component
@ConditionalOnProperty(prefix = "imlate.notification.heartbeat", name = "publisher",
        havingValue = "log", matchIfMissing = true)
public class LogDispatchHeartbeatPublisher implements DispatchHeartbeatPublisher {

    private static final Logger log = LoggerFactory.getLogger(LogDispatchHeartbeatPublisher.class);

    /** 지표 필터가 찾는 하트비트 표식. 바꾸면 인프라의 필터도 함께 바꿔야 한다. */
    public static final String HEARTBEAT_MARKER = "IMLATE_HEARTBEAT";
    /** 지표 필터가 찾는 실패 표식(1줄 = 실패 1건). */
    public static final String FAILURE_MARKER = "IMLATE_DISPATCH_FAILURE";

    /** 한 줄을 유지하기 위한 사유 문자열 최대 길이. */
    private static final int MAX_REASON_LENGTH = 180;

    private final NotificationProperties properties;

    public LogDispatchHeartbeatPublisher(NotificationProperties properties) {
        this.properties = properties;
    }

    @Override
    public void publish(DispatchHeartbeat heartbeat) {
        if (heartbeat == null) {
            return;
        }
        try {
            NotificationProperties.Heartbeat config = properties.heartbeat();
            // 지표 1: DispatchCompleted — "발송 시도가 끝났다"는 사실 자체(결과 무관).
            //   표식 + kind 를 앞에 붙여 "IMLATE_HEARTBEAT kind=DISPATCH" 한 덩어리로 필터링되게 한다.
            log.info("{} kind={} namespace={} metric=DispatchCompleted value=1 Environment={} "
                            + "date={} result={} target={} failures={}",
                    HEARTBEAT_MARKER, heartbeat.kind(), config.namespace(), config.environment(),
                    heartbeat.date(), heartbeat.result(), heartbeat.targetCount(), heartbeat.failureCount());

            // 지표 2: DispatchFailures — 실패 1건당 한 줄. 지표 필터 SUM 이 곧 실패 건수가 된다.
            for (ChannelFailure failure : heartbeat.failures()) {
                log.error("{} namespace={} metric=DispatchFailures value=1 Environment={} date={} "
                                + "channel={} to={} reason={}",
                        FAILURE_MARKER, config.namespace(), config.environment(), heartbeat.date(),
                        failure.channel(), failure.maskedRecipient(), oneLine(failure.reason()));
            }
        } catch (Exception ex) {
            // 지표 발행 실패가 발송 흐름을 깨뜨리면 안 된다.
            log.warn("하트비트 기록에 실패했습니다(발송 결과에는 영향 없음). cause={}", ex.toString());
        }
    }

    /** 줄바꿈이 섞이면 지표 필터가 한 줄로 읽지 못하므로 공백으로 눕히고 길이를 제한한다. */
    private static String oneLine(String value) {
        if (value == null || value.isBlank()) {
            return "(사유없음)";
        }
        String flattened = value.replaceAll("\\s+", " ").trim();
        return flattened.length() <= MAX_REASON_LENGTH
                ? flattened : flattened.substring(0, MAX_REASON_LENGTH) + "...";
    }
}
