package com.skala.imlate.notification.ops;

/**
 * 발송 완료 하트비트 발행기.
 *
 * <p>구현체는 <b>절대 예외를 전파하지 않는다.</b> 지표 전송 실패 때문에 사감 발송 결과가
 * 달라지는 일은 없어야 한다(부작용 금지).
 */
public interface DispatchHeartbeatPublisher {

    /**
     * 하트비트를 발행한다.
     *
     * @param heartbeat 발송 시도 결과 신호
     */
    void publish(DispatchHeartbeat heartbeat);
}
