package com.skala.imlate.notification.scheduler;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.NotificationProperties;
import com.skala.imlate.notification.service.CurfewNotificationService;
import com.skala.imlate.notification.service.DispatchSummary;

/**
 * 사감 발송 스케줄러(R3).
 *
 * <p>등록 마감(22:00) 10분 뒤인 <b>22:10</b> 에 당일 명단을 발송하고,
 * 22:25 / 22:40 에 실패한 채널만 재시도한다(cron 은 설정값).
 * {@code imlate.notification.enabled=false} 면 아무것도 하지 않는다.
 *
 * <p>스케줄러 스레드가 죽지 않도록 모든 예외를 잡아 로그로만 남긴다.
 */
@Component
public class CurfewNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(CurfewNotificationScheduler.class);

    private final CurfewNotificationService notificationService;
    private final NotificationProperties properties;
    private final Clock clock;

    public CurfewNotificationScheduler(CurfewNotificationService notificationService,
                                       NotificationProperties properties,
                                       Clock clock) {
        this.notificationService = notificationService;
        this.properties = properties;
        this.clock = clock;
    }

    /** 22:10 정기 발송. */
    @Scheduled(cron = "${imlate.notification.dispatch-cron}", zone = "${imlate.timezone}")
    public void dispatchAt2210() {
        if (!properties.enabled()) {
            log.debug("사감 발송이 비활성화되어 있어 정기 발송을 건너뜁니다.");
            return;
        }
        LocalDate today = LocalDate.now(clock);
        log.info("정기 사감 발송을 시작합니다. date={}", today);
        try {
            DispatchSummary summary = notificationService.dispatch(today, false);
            logSummary("정기 발송", summary);
        } catch (Exception ex) {
            // 스케줄러 스레드는 절대 예외로 중단시키지 않는다.
            log.error("정기 사감 발송 중 예기치 못한 오류가 발생했습니다. date={}", today, ex);
        }
    }

    /** 실패한 채널만 재발송. */
    @Scheduled(cron = "${imlate.notification.retry-cron}", zone = "${imlate.timezone}")
    public void retryFailed() {
        if (!properties.enabled()) {
            log.debug("사감 발송이 비활성화되어 있어 재시도를 건너뜁니다.");
            return;
        }
        LocalDate today = LocalDate.now(clock);
        try {
            DispatchSummary summary = notificationService.retryFailed(today);
            logSummary("실패 재시도", summary);
        } catch (Exception ex) {
            log.error("사감 발송 재시도 중 예기치 못한 오류가 발생했습니다. date={}", today, ex);
        }
    }

    private static void logSummary(String label, DispatchSummary summary) {
        if (summary.skipped()) {
            log.info("{} 건너뜀: date={}, 사유={}", label, summary.date(), summary.skipReason());
            return;
        }
        log.info("{} 결과: date={}, 인원={}명, SMS {}/{} 성공, EMAIL {}/{} 성공",
                label, summary.date(), summary.targetCount(),
                summary.smsSuccess(), summary.smsSuccess() + summary.smsFailed(),
                summary.emailSuccess(), summary.emailSuccess() + summary.emailFailed());
    }
}
