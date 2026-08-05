package com.skala.imlate.notification.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.skala.imlate.common.properties.ImlateProperties;
import com.skala.imlate.common.properties.NotificationProperties;
import com.skala.imlate.common.security.AccessTokenService;
import com.skala.imlate.notification.channel.EmailSender;
import com.skala.imlate.notification.channel.PhoneNumbers;
import com.skala.imlate.notification.channel.SendResult;
import com.skala.imlate.notification.channel.SmsSender;
import com.skala.imlate.notification.domain.NotificationDispatch;
import com.skala.imlate.notification.domain.NotificationDispatchRepository;
import com.skala.imlate.notification.template.CurfewNoticeRenderer;
import com.skala.imlate.notification.template.NoticePayload;
import com.skala.imlate.registration.domain.ReturnRegistration;
import com.skala.imlate.registration.service.ReconciliationReport;
import com.skala.imlate.registration.service.ReconciliationService;
import com.skala.imlate.registration.service.RegistrationService;
import com.skala.imlate.stats.StatsQueryService;
import com.skala.imlate.stats.StatsSnapshot;

/**
 * 사감 발송 오케스트레이션(R3, R9).
 *
 * <p>흐름: 활성화 확인 → 분산 락 → 중복 발송 확인 → Redis WAL ↔ DB 대사(누락 복구 포함) →
 * 명단 조회(<b>0명이면 발송하지 않음</b>) → 통계 조회 → 문구 렌더 → 사감별 문자/이메일 발송(재시도 포함) →
 * 이력 저장 → 락 해제.
 *
 * <p>메서드에 {@code @Transactional} 을 걸지 않는다. 외부 발송(수 초 소요)이 DB 트랜잭션을 붙잡으면
 * 커넥션이 고갈되기 때문이다. 이력 저장은 리포지토리 단위 트랜잭션으로 개별 커밋된다.
 */
@Service
public class CurfewNotificationService {

    private static final Logger log = LoggerFactory.getLogger(CurfewNotificationService.class);

    /** 재시도 백오프 기준(1s → 2s → 4s …). */
    private static final long BASE_BACKOFF_MILLIS = 1000L;
    /** 백오프 상한(스케줄러 스레드를 오래 붙잡지 않도록). */
    private static final long MAX_BACKOFF_MILLIS = 8000L;

    private final NotificationProperties notificationProperties;
    private final ImlateProperties imlateProperties;
    private final RegistrationService registrationService;
    private final ReconciliationService reconciliationService;
    private final StatsQueryService statsQueryService;
    private final AccessTokenService accessTokenService;
    private final CurfewNoticeRenderer renderer;
    private final SmsSender smsSender;
    private final EmailSender emailSender;
    private final NotificationDispatchRepository dispatchRepository;
    private final DispatchLockManager lockManager;
    private final Clock clock;

    public CurfewNotificationService(NotificationProperties notificationProperties,
                                     ImlateProperties imlateProperties,
                                     RegistrationService registrationService,
                                     ReconciliationService reconciliationService,
                                     StatsQueryService statsQueryService,
                                     AccessTokenService accessTokenService,
                                     CurfewNoticeRenderer renderer,
                                     SmsSender smsSender,
                                     EmailSender emailSender,
                                     NotificationDispatchRepository dispatchRepository,
                                     DispatchLockManager lockManager,
                                     Clock clock) {
        this.notificationProperties = notificationProperties;
        this.imlateProperties = imlateProperties;
        this.registrationService = registrationService;
        this.reconciliationService = reconciliationService;
        this.statsQueryService = statsQueryService;
        this.accessTokenService = accessTokenService;
        this.renderer = renderer;
        this.smsSender = smsSender;
        this.emailSender = emailSender;
        this.dispatchRepository = dispatchRepository;
        this.lockManager = lockManager;
        this.clock = clock;
    }

    /**
     * 사감 발송을 수행한다.
     *
     * @param date  대상일(null 이면 오늘)
     * @param force true 면 이미 성공 이력이 있어도 재발송하며, 발송 기능이 꺼져 있어도 강제로 진행한다
     */
    public DispatchSummary dispatch(LocalDate date, boolean force) {
        LocalDate target = resolveDate(date);
        if (!notificationProperties.enabled() && !force) {
            log.info("사감 발송이 비활성화되어 있어 실행하지 않습니다(imlate.notification.enabled=false). date={}", target);
            return DispatchSummary.ofSkipped(target, "DISABLED");
        }
        return execute(target, force, null);
    }

    /** 실패 이력이 남아 있는 채널만 재발송한다. */
    public DispatchSummary retryFailed(LocalDate date) {
        LocalDate target = resolveDate(date);
        if (!notificationProperties.enabled()) {
            log.info("사감 발송이 비활성화되어 있어 재시도하지 않습니다. date={}", target);
            return DispatchSummary.ofSkipped(target, "DISABLED");
        }
        Set<String> pending = pendingFailures(target);
        if (pending.isEmpty()) {
            log.info("재시도할 실패 이력이 없습니다. date={}", target);
            return DispatchSummary.ofSkipped(target, "NO_FAILURE");
        }
        log.info("실패 채널 재시도를 시작합니다. date={}, targets={}", target, pending);
        // 재시도는 이미 성공한 채널을 건드리지 않으므로 force=true 로 중복 판정을 건너뛴다.
        return execute(target, true, pending);
    }

    /** 실제 발송 없이 렌더 결과만 만들어 본다(관리 API preview 용). 대사도 복구 없이 조회만 한다. */
    public NoticePreview preview(LocalDate date) {
        LocalDate target = resolveDate(date);
        NoticePayload payload = buildPayload(target, false);
        return new NoticePreview(target, payload.totalCount(), payload.lookupUrl(),
                renderer.smsTitle(payload), renderer.smsBody(payload),
                renderer.emailSubject(payload), renderer.emailText(payload), renderer.emailHtml(payload));
    }

    // ------------------------------------------------------------------
    // 내부 구현
    // ------------------------------------------------------------------

    private DispatchSummary execute(LocalDate date, boolean force, Set<String> restrictTo) {
        String lockToken = UUID.randomUUID().toString();
        if (!lockManager.tryAcquire(date, lockToken)) {
            log.warn("다른 인스턴스가 이미 발송 중이라 건너뜁니다. date={}", date);
            return DispatchSummary.ofSkipped(date, "LOCK_NOT_ACQUIRED");
        }
        try {
            if (!force && dispatchRepository.countByDispatchDateAndStatus(
                    date, NotificationDispatch.STATUS_SUCCESS) > 0) {
                log.info("이미 성공한 발송 이력이 있어 재발송하지 않습니다. date={}", date);
                return DispatchSummary.ofSkipped(date, "ALREADY_SENT");
            }

            // WAL ↔ DB 대사(누락분 복구 포함) 후 명단을 읽는다.
            NoticePayload payload = buildPayload(date, true);
            if (payload.totalCount() == 0) {
                // 요구사항: 1명도 없으면 발송하지 않는다.
                log.info("복귀 등록 인원이 0명이라 사감 발송을 하지 않습니다. date={}", date);
                return DispatchSummary.ofSkipped(date, "NO_REGISTRATION");
            }

            List<NotificationProperties.Supervisor> supervisors = notificationProperties.supervisors();
            if (supervisors.isEmpty()) {
                log.warn("수신 사감이 설정되어 있지 않습니다(imlate.notification.supervisors). date={}, count={}",
                        date, payload.totalCount());
                return DispatchSummary.ofSkipped(date, "NO_SUPERVISOR", payload.totalCount());
            }

            String smsTitle = renderer.smsTitle(payload);
            String smsBody = renderer.smsBody(payload);
            String emailSubject = renderer.emailSubject(payload);
            String emailText = renderer.emailText(payload);
            String emailHtml = renderer.emailHtml(payload);
            int targetCount = payload.totalCount();

            int smsSuccess = 0;
            int smsFailed = 0;
            int emailSuccess = 0;
            int emailFailed = 0;

            for (NotificationProperties.Supervisor supervisor : supervisors) {
                // ---- 문자 ----
                String phone = PhoneNumbers.normalize(supervisor.phone());
                if (phone.isBlank()) {
                    if (restrictTo == null) {
                        saveSkipped(date, NotificationDispatch.CHANNEL_SMS, supervisor.name(),
                                targetCount, "전화번호가 설정되지 않았습니다.");
                    }
                } else if (isTargeted(restrictTo, NotificationDispatch.CHANNEL_SMS, phone)) {
                    boolean ok = sendWithRetry(date, NotificationDispatch.CHANNEL_SMS, supervisor.name(),
                            phone, targetCount, PhoneNumbers.mask(phone),
                            () -> smsSender.send(phone, smsTitle, smsBody));
                    if (ok) {
                        smsSuccess++;
                    } else {
                        smsFailed++;
                    }
                }

                // ---- 이메일 ----
                String email = supervisor.email() == null ? "" : supervisor.email().trim();
                if (email.isBlank()) {
                    if (restrictTo == null) {
                        saveSkipped(date, NotificationDispatch.CHANNEL_EMAIL, supervisor.name(),
                                targetCount, "이메일 주소가 설정되지 않았습니다.");
                    }
                } else if (isTargeted(restrictTo, NotificationDispatch.CHANNEL_EMAIL, email)) {
                    boolean ok = sendWithRetry(date, NotificationDispatch.CHANNEL_EMAIL, supervisor.name(),
                            email, targetCount, PhoneNumbers.maskEmail(email),
                            () -> emailSender.send(email, emailSubject, emailText, emailHtml));
                    if (ok) {
                        emailSuccess++;
                    } else {
                        emailFailed++;
                    }
                }
            }

            DispatchSummary summary = DispatchSummary.ofSent(date, targetCount,
                    smsSuccess, smsFailed, emailSuccess, emailFailed, payload.lookupUrl());
            log.info("사감 발송 완료: date={}, 인원={}명, SMS 성공/실패={}/{}, EMAIL 성공/실패={}/{}, 조회URL={}",
                    date, targetCount, smsSuccess, smsFailed, emailSuccess, emailFailed, payload.lookupUrl());
            return summary;
        } finally {
            lockManager.release(date, lockToken);
        }
    }

    /** 안내문 입력 묶음을 만든다. {@code recover=true} 면 WAL 누락분을 DB 로 복구한다. */
    private NoticePayload buildPayload(LocalDate date, boolean recover) {
        ReconciliationReport verification = reconcile(date, recover);
        List<ReturnRegistration> registrations = findRegistrations(date);
        StatsSnapshot stats = snapshot();

        List<NoticePayload.Row> rows = new ArrayList<>(registrations.size());
        int no = 1;
        for (ReturnRegistration registration : registrations) {
            rows.add(new NoticePayload.Row(no++, registration.getClassName(),
                    registration.getStudentName(), registration.getRoomNumber()));
        }
        return new NoticePayload(date, rows, buildLookupUrl(date),
                imlateProperties.registration().returnTime(),
                imlateProperties.registration().curfewTime(),
                verification, stats);
    }

    /** 대사 실행. 실패해도 발송 자체는 계속한다(검증 정보만 비운다). */
    private ReconciliationReport reconcile(LocalDate date, boolean recover) {
        try {
            return recover ? reconciliationService.reconcile(date) : reconciliationService.inspect(date);
        } catch (Exception ex) {
            log.warn("Redis WAL ↔ DB 대사에 실패했습니다(발송은 계속 진행). date={}, cause={}", date, ex.toString());
            return null;
        }
    }

    private List<ReturnRegistration> findRegistrations(LocalDate date) {
        List<ReturnRegistration> registrations = registrationService.findByDate(date);
        return registrations == null ? List.of() : registrations;
    }

    /** 통계 조회. 실패해도 발송을 막지 않는다. */
    private StatsSnapshot snapshot() {
        try {
            return statsQueryService.snapshot();
        } catch (Exception ex) {
            log.warn("통계 조회에 실패했습니다(발송은 계속 진행). cause={}", ex.toString());
            return null;
        }
    }

    /** 조회 페이지 URL(토큰 포함). */
    private String buildLookupUrl(LocalDate date) {
        String base = imlateProperties.lookup().baseUrl();
        if (base == null) {
            base = "";
        }
        base = base.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // 토큰은 base64url 이라 URL 인코딩 없이 그대로 붙여도 안전하다.
        return base + "/lookup?date=" + date + "&token=" + accessTokenService.issue(date);
    }

    /** 재시도 대상 제한(restrictTo)이 없으면 전부, 있으면 해당 채널/수신처만 발송한다. */
    private static boolean isTargeted(Set<String> restrictTo, String channel, String recipient) {
        return restrictTo == null || restrictTo.contains(historyKey(channel, recipient));
    }

    private static String historyKey(String channel, String recipient) {
        return channel + "|" + recipient;
    }

    /** 아직 성공하지 못한(FAILED 만 있는) 채널/수신처 집합. */
    private Set<String> pendingFailures(LocalDate date) {
        List<NotificationDispatch> history = dispatchRepository.findByDispatchDate(date);
        Set<String> succeeded = new HashSet<>();
        Set<String> failed = new LinkedHashSet<>();
        for (NotificationDispatch dispatch : history) {
            String key = historyKey(dispatch.getChannel(), dispatch.getRecipient());
            if (NotificationDispatch.STATUS_SUCCESS.equals(dispatch.getStatus())) {
                succeeded.add(key);
            } else if (NotificationDispatch.STATUS_FAILED.equals(dispatch.getStatus())) {
                failed.add(key);
            }
        }
        failed.removeAll(succeeded);
        return failed;
    }

    /**
     * 한 채널을 최대 {@code maxAttempts} 회까지 시도한다. 실패 시 1s → 2s → 4s 백오프.
     *
     * @return 성공 여부
     */
    private boolean sendWithRetry(LocalDate date, String channel, String recipientName, String recipient,
                                  int targetCount, String maskedRecipient, Supplier<SendResult> action) {
        int maxAttempts = Math.max(1, notificationProperties.maxAttempts());
        SendResult last = SendResult.fail("발송이 시도되지 않았습니다.");

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            last = safeSend(action);
            if (last.success()) {
                dispatchRepository.save(NotificationDispatch.success(date, channel, recipientName, recipient,
                        attempt, targetCount, last.providerMessageId(), LocalDateTime.now(clock)));
                log.info("{} 발송 성공: to={}, attempt={}/{}, msgId={}",
                        channel, maskedRecipient, attempt, maxAttempts, last.providerMessageId());
                return true;
            }
            log.warn("{} 발송 실패: to={}, attempt={}/{}, reason={}",
                    channel, maskedRecipient, attempt, maxAttempts, last.errorMessage());
            if (attempt < maxAttempts && !sleepBackoff(attempt)) {
                // 인터럽트되면 더 이상 재시도하지 않는다.
                break;
            }
        }

        dispatchRepository.save(NotificationDispatch.failed(date, channel, recipientName, recipient,
                maxAttempts, targetCount, last.errorMessage(), LocalDateTime.now(clock)));
        log.error("{} 발송 최종 실패: to={}, date={}, reason={}",
                channel, maskedRecipient, date, last.errorMessage());
        return false;
    }

    /** 구현체가 계약을 어기고 예외를 던지더라도 스케줄러가 죽지 않도록 한 번 더 감싼다. */
    private static SendResult safeSend(Supplier<SendResult> action) {
        try {
            SendResult result = action.get();
            return result == null ? SendResult.fail("발송기가 결과를 반환하지 않았습니다.") : result;
        } catch (Exception ex) {
            return SendResult.fail("발송기 예외: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
        }
    }

    /** 지수 백오프 대기. 인터럽트되면 false 를 반환하고 인터럽트 상태를 복원한다. */
    private static boolean sleepBackoff(int attempt) {
        long millis = Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS * (1L << Math.min(attempt - 1, 10)));
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("발송 재시도 대기 중 인터럽트되어 재시도를 중단합니다.");
            return false;
        }
    }

    private void saveSkipped(LocalDate date, String channel, String recipientName,
                             int targetCount, String reason) {
        log.warn("{} 발송 건너뜀: 사감={}, 사유={}", channel, recipientName, reason);
        dispatchRepository.save(NotificationDispatch.skipped(date, channel, recipientName,
                null, targetCount, reason, LocalDateTime.now(clock)));
    }

    private LocalDate resolveDate(LocalDate date) {
        return date != null ? date : LocalDate.now(clock);
    }

    /**
     * 관리 API preview 응답(실제 발송 없이 렌더 결과만).
     *
     * @param date         대상일
     * @param targetCount  명단 인원 수
     * @param lookupUrl    조회 페이지 URL
     * @param smsTitle     문자 제목(LMS)
     * @param smsBody      문자 본문
     * @param emailSubject 이메일 제목
     * @param emailText    이메일 텍스트 본문
     * @param emailHtml    이메일 HTML 본문
     */
    public record NoticePreview(LocalDate date, int targetCount, String lookupUrl,
                                String smsTitle, String smsBody,
                                String emailSubject, String emailText, String emailHtml) {
    }
}
