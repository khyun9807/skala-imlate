package com.skala.imlate.notification.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 하트비트를 끈 경우({@code imlate.notification.heartbeat.enabled=false})의 빈 구현.
 *
 * <p>{@link LogDispatchHeartbeatPublisher} 와 조건이 정확히 상호배타적이라
 * 둘 중 하나만 등록된다(=주입 지점은 항상 하나의 빈을 얻는다).
 * 로컬·테스트에서 이 구현이 선택되며, 자격증명이나 외부 호출이 전혀 없다.
 */
@Component
@ConditionalOnProperty(prefix = "imlate.notification.heartbeat", name = "publisher", havingValue = "none")
public class NoopDispatchHeartbeatPublisher implements DispatchHeartbeatPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopDispatchHeartbeatPublisher.class);

    @Override
    public void publish(DispatchHeartbeat heartbeat) {
        if (heartbeat == null) {
            return;
        }
        log.debug("하트비트가 비활성화되어 있어 기록하지 않습니다. kind={}, date={}, result={}",
                heartbeat.kind(), heartbeat.date(), heartbeat.result());
    }
}
