package com.skala.imlate.stats.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.skala.imlate.registration.event.RegistrationCreatedEvent;

/**
 * 등록 성공 이벤트를 받아 등록 통계를 올리는 리스너(SPEC §7.1).
 *
 * <p>{@code registration} 모듈은 {@code ApplicationEventPublisher} 로 이벤트만 발행하고,
 * 카운팅은 통계 모듈이 담당한다. 트랜잭션이 실제로 커밋된 뒤에만 집계하므로
 * 롤백된 등록이 통계에 반영되지 않는다.
 */
@Component
public class RegistrationStatsListener {

    private static final Logger log = LoggerFactory.getLogger(RegistrationStatsListener.class);

    private final StatsRecorder statsRecorder;

    public RegistrationStatsListener(StatsRecorder statsRecorder) {
        this.statsRecorder = statsRecorder;
    }

    /**
     * 등록 커밋 후 {@code imlate:stats:reg:{date}} 와 누적 등록 수를 증가시킨다.
     *
     * <p>{@code fallbackExecution = true} 로 두어 트랜잭션 밖에서 발행된 이벤트도 유실되지 않게 한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRegistrationCreated(RegistrationCreatedEvent event) {
        if (event == null || event.date() == null) {
            return;
        }
        try {
            statsRecorder.recordRegistration(event.date());
            log.debug("등록 통계 반영: date={}, className={}", event.date(), event.className());
        } catch (Exception ex) {
            // 이미 커밋된 등록을 되돌릴 수는 없으므로 통계 실패는 로그만 남긴다.
            log.warn("등록 통계 반영 실패(무시): date={}, cause={}", event.date(), ex.toString());
        }
    }
}
