package com.skala.imlate.notification.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.skala.imlate.common.security.AdminKeyGuard;
import com.skala.imlate.notification.domain.NotificationDispatch;
import com.skala.imlate.notification.domain.NotificationDispatchRepository;
import com.skala.imlate.notification.service.CurfewNotificationService;
import com.skala.imlate.notification.service.DispatchSummary;

/**
 * 사감 발송 관리 API(SPEC §6.5). 모든 요청은 {@code X-Admin-Key} 헤더로 보호된다.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
public class AdminNotificationController {

    private static final Logger log = LoggerFactory.getLogger(AdminNotificationController.class);

    private final AdminKeyGuard adminKeyGuard;
    private final CurfewNotificationService notificationService;
    private final NotificationDispatchRepository dispatchRepository;
    private final Clock clock;

    public AdminNotificationController(AdminKeyGuard adminKeyGuard,
                                       CurfewNotificationService notificationService,
                                       NotificationDispatchRepository dispatchRepository,
                                       Clock clock) {
        this.adminKeyGuard = adminKeyGuard;
        this.notificationService = notificationService;
        this.dispatchRepository = dispatchRepository;
        this.clock = clock;
    }

    /** 수동 발송. {@code force=true} 면 이미 성공 이력이 있어도 다시 보낸다. */
    @PostMapping("/dispatch")
    public DispatchSummary dispatch(
            @RequestHeader(value = AdminKeyGuard.HEADER_NAME, required = false) String adminKey,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        adminKeyGuard.require(adminKey);
        LocalDate target = resolveDate(date);
        log.info("[ADMIN] 수동 발송 요청: date={}, force={}", target, force);
        return notificationService.dispatch(target, force);
    }

    /** 실패한 채널만 재발송. */
    @PostMapping("/retry")
    public DispatchSummary retry(
            @RequestHeader(value = AdminKeyGuard.HEADER_NAME, required = false) String adminKey,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        adminKeyGuard.require(adminKey);
        LocalDate target = resolveDate(date);
        log.info("[ADMIN] 실패 재시도 요청: date={}", target);
        return notificationService.retryFailed(target);
    }

    /** 해당 일자의 발송 이력 조회. */
    @GetMapping
    public DispatchHistoryResponse history(
            @RequestHeader(value = AdminKeyGuard.HEADER_NAME, required = false) String adminKey,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        adminKeyGuard.require(adminKey);
        LocalDate target = resolveDate(date);
        List<NotificationDispatch> history = dispatchRepository.findByDispatchDateOrderByIdAsc(target);
        List<DispatchView> items = new ArrayList<>(history.size());
        for (NotificationDispatch dispatch : history) {
            items.add(DispatchView.from(dispatch));
        }
        return new DispatchHistoryResponse(target, items.size(), items);
    }

    /** 실제 발송 없이 렌더 결과만 확인한다. */
    @PostMapping("/preview")
    public CurfewNotificationService.NoticePreview preview(
            @RequestHeader(value = AdminKeyGuard.HEADER_NAME, required = false) String adminKey,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        adminKeyGuard.require(adminKey);
        LocalDate target = resolveDate(date);
        log.info("[ADMIN] 발송 미리보기 요청: date={}", target);
        return notificationService.preview(target);
    }

    private LocalDate resolveDate(LocalDate date) {
        return date != null ? date : LocalDate.now(clock);
    }

    /**
     * 발송 이력 목록 응답.
     *
     * @param date  대상일
     * @param count 이력 건수
     * @param items 이력 목록
     */
    public record DispatchHistoryResponse(LocalDate date, int count, List<DispatchView> items) {
    }

    /**
     * 발송 이력 1건(엔티티를 그대로 노출하지 않기 위한 뷰).
     *
     * @param id                이력 PK
     * @param channel           SMS | EMAIL
     * @param recipientName     수신 사감 이름
     * @param recipient         수신처(마스킹하지 않음 — 관리자 전용 API)
     * @param status            SUCCESS | FAILED | SKIPPED
     * @param attempt           시도 횟수
     * @param targetCount       발송 시점 명단 인원 수
     * @param providerMessageId 제공자 메시지 ID
     * @param errorMessage      실패 사유
     * @param sentAt            발송 시각
     */
    public record DispatchView(Long id, String channel, String recipientName, String recipient,
                               String status, int attempt, int targetCount,
                               String providerMessageId, String errorMessage, LocalDateTime sentAt) {

        static DispatchView from(NotificationDispatch dispatch) {
            return new DispatchView(dispatch.getId(), dispatch.getChannel(), dispatch.getRecipientName(),
                    dispatch.getRecipient(), dispatch.getStatus(), dispatch.getAttempt(),
                    dispatch.getTargetCount(), dispatch.getProviderMessageId(),
                    dispatch.getErrorMessage(), dispatch.getSentAt());
        }
    }
}
