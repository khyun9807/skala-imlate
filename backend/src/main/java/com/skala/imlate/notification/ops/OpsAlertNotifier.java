package com.skala.imlate.notification.ops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.NotificationProperties;
import com.skala.imlate.notification.channel.EmailSender;
import com.skala.imlate.notification.channel.PhoneNumbers;
import com.skala.imlate.notification.channel.SendResult;
import com.skala.imlate.notification.channel.SmsSender;
import com.skala.imlate.notification.service.DispatchSummary;

/**
 * 발송 결과를 <b>운영자</b>에게 알린다(위험 2·3).
 *
 * <p>지금까지 발송 실패는 {@code notification_dispatch} 테이블에만 남고 아무도 몰랐다. 실제로 운영 중
 * 알리고 IP 미등록·SES 미검증으로 두 번 실패했는데 관리 API 를 직접 조회해서야 알았다. 이 클래스는
 * 그 침묵을 없앤다.
 *
 * <p><b>지켜야 할 세 가지</b>
 * <ol>
 *   <li><b>수신처 분리</b> — 운영 알림은 사감에게 가면 안 된다. 사감이 명단 문자와 혼동한다.
 *       그래서 수신처는 {@code imlate.notification.ops-alert.*} 로 따로 두고, 문자 번호는
 *       기본이 빈 값이다. 혹시 사감 번호와 같은 값이 들어오면 발송하지 않고 오류 로그만 남긴다.</li>
 *   <li><b>부작용 금지</b> — 알림 때문에 사감 발송이 느려지거나 실패하면 안 된다. 모든 경로를
 *       try/catch 로 감싸고 예외를 절대 밖으로 던지지 않는다.</li>
 *   <li><b>무한 루프 금지</b> — 알림 발송이 실패해도 그 실패로 다시 알리지 않는다.
 *       결과는 로그로만 남기며, 재진입 가드까지 둔다.</li>
 * </ol>
 */
@Component
public class OpsAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertNotifier.class);

    /** 실패 사유 요약에 노출할 최대 원인 개수(가장 흔한 것부터). */
    private static final int MAX_REASON_GROUPS = 2;
    /** 사유 그룹핑 키 길이(뒤쪽 가변 문자열까지 넣으면 같은 원인이 흩어진다). */
    private static final int REASON_KEY_LENGTH = 120;
    /** 인원이 있는데 수신 사감이 없어 아무에게도 못 보낸 상태(= 사실상 전면 실패). */
    private static final String SKIP_NO_SUPERVISOR = "NO_SUPERVISOR";

    /**
     * 실패 사유 → 조치 힌트. 위에서부터 먼저 걸리는 것 하나를 쓴다.
     * 실제로 겪은 두 사고(알리고 허용 IP, SES 미검증)를 맨 앞에 둔다.
     */
    private static final List<Hint> HINTS = List.of(
            new Hint("result_code=-101", "알리고 관리자 페이지에서 API 연동 허용 IP 를 확인하세요(서버 공인 IP 가 바뀌면 재등록해야 합니다)."),
            new Hint("not verified", "SES 발신/수신 주소의 검증 상태를 확인하세요(샌드박스면 수신 주소도 검증 대상입니다)."),
            new Hint("MessageRejected", "SES 가 메일을 거부했습니다 — 발신 아이덴티티 검증 상태를 확인하세요."),
            new Hint("result_code=-102", "알리고 발신번호 사전등록과 API 키/사용자 ID 를 확인하세요."),
            new Hint("잔여", "알리고 잔여 건수를 충전하세요."),
            new Hint("not authorized", "EC2 인스턴스 역할의 ses:SendEmail 권한을 확인하세요."),
            new Hint("AccessDenied", "EC2 인스턴스 역할 권한(SES/SSM)을 확인하세요."),
            new Hint("Throttl", "SES 전송률 한도를 초과했습니다 — 재시도로 해소되는지 확인하세요."),
            new Hint("quota", "SES 일일 발송 한도를 초과했습니다."),
            new Hint("Timeout", "외부 API 응답이 지연됐습니다 — 보안그룹/아웃바운드 통신을 확인하세요."),
            new Hint("Connect", "외부 API 연결에 실패했습니다 — 아웃바운드 통신과 DNS 를 확인하세요."),
            new Hint("설정", "해당 채널 설정값(API 키·발신번호·발신주소)이 비어 있는지 확인하세요."));

    private final NotificationProperties properties;
    private final SmsSender smsSender;
    private final EmailSender emailSender;
    private final SmsBalanceMonitor balanceMonitor;

    /**
     * 재진입 가드. 알림을 보내는 도중에 다시 알림 요청이 들어오면 무시한다.
     * (현재 호출 경로에는 재귀가 없지만, 나중에 누가 알림 실패를 다시 알리도록 고쳐도
     *  무한 루프로 번지지 않게 막아 둔다.)
     */
    private final ThreadLocal<Boolean> sending = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public OpsAlertNotifier(NotificationProperties properties,
                            SmsSender smsSender,
                            EmailSender emailSender,
                            SmsBalanceMonitor balanceMonitor) {
        this.properties = properties;
        this.smsSender = smsSender;
        this.emailSender = emailSender;
        this.balanceMonitor = balanceMonitor;
    }

    /**
     * 발송이 끝난 뒤 호출한다. <b>어떤 경우에도 예외를 던지지 않는다.</b>
     *
     * @param summary  발송 결과 요약
     * @param failures 최종 실패 목록(없으면 빈 목록)
     */
    public void notifyDispatchResult(DispatchSummary summary, List<ChannelFailure> failures) {
        try {
            if (summary == null) {
                return;
            }
            NotificationProperties.OpsAlert config = properties.opsAlert();
            if (!config.enabled()) {
                log.debug("운영자 알림이 비활성화되어 있어 보내지 않습니다(imlate.notification.ops-alert.enabled=false).");
                return;
            }
            if (Boolean.TRUE.equals(sending.get())) {
                // 알림을 보내는 중에 다시 들어온 요청 — 알림이 알림을 부르는 고리를 여기서 끊는다.
                log.warn("운영자 알림 처리 중 재진입이 감지되어 무시합니다(무한 루프 방지).");
                return;
            }

            List<ChannelFailure> failureList = failures == null ? List.of() : List.copyOf(failures);
            // 잔액은 실제로 발송을 시도한 날에만 확인한다(건너뛴 날은 문자가 나가지 않았다).
            // 하루 1회 제한은 SmsBalanceMonitor 가 담당한다.
            String balanceWarning = summary.skipped()
                    ? null : balanceMonitor.checkOnce(summary.date()).orElse(null);

            boolean criticalSkip = isCriticalSkip(summary);
            // 잔액 경고도 알림 사유로 본다. 로그만 남기면 위험 3(잔액 소진)이 여전히 "조용한" 상태이기 때문이다.
            boolean alertWorthy = !failureList.isEmpty() || criticalSkip || balanceWarning != null;
            if (!alertWorthy && !config.notifyOnSuccess()) {
                log.debug("발송이 모두 성공해 운영자 알림을 보내지 않습니다(notify-on-success=false). date={}",
                        summary.date());
                return;
            }

            sending.set(Boolean.TRUE);
            try {
                dispatchAlert(config, summary, failureList, criticalSkip, balanceWarning);
            } finally {
                sending.remove();
            }
        } catch (Exception ex) {
            // 알림 실패가 사감 발송 결과를 바꾸면 안 된다. 여기서 끝낸다.
            log.error("운영자 알림 처리 중 오류가 발생했습니다(사감 발송 결과에는 영향 없음).", ex);
        }
    }

    // ------------------------------------------------------------------
    // 발송
    // ------------------------------------------------------------------

    private void dispatchAlert(NotificationProperties.OpsAlert config, DispatchSummary summary,
                               List<ChannelFailure> failures, boolean criticalSkip, String balanceWarning) {
        List<ReasonGroup> groups = summarizeReasons(failures);
        String subject = buildSubject(summary, failures, criticalSkip, balanceWarning);
        String text = buildEmailText(summary, failures, groups, criticalSkip, balanceWarning);

        sendEmail(config, subject, text);
        sendSms(config, subject, buildSmsBody(summary, groups, criticalSkip, balanceWarning));
    }

    private void sendEmail(NotificationProperties.OpsAlert config, String subject, String text) {
        String email = config.email();
        if (email == null || email.isBlank()) {
            log.warn("운영자 알림 수신 메일이 비어 있어 메일 알림을 보내지 않습니다"
                    + "(imlate.notification.ops-alert.email).");
            return;
        }
        SendResult result = safeSend(() -> emailSender.send(email, subject, text, toHtml(text)));
        if (result.success()) {
            log.info("운영자 알림 메일을 보냈습니다. to={}", PhoneNumbers.maskEmail(email));
        } else {
            // 여기서 또 알리면 무한 루프다. 로그로만 남긴다.
            log.error("운영자 알림 메일 발송에 실패했습니다(재알림하지 않음). to={}, reason={}",
                    PhoneNumbers.maskEmail(email), result.errorMessage());
        }
    }

    private void sendSms(NotificationProperties.OpsAlert config, String title, String body) {
        if (!config.hasPhone()) {
            // 기본값이 빈 값인 이유가 이것이다 — 잘못 설정하면 그 문자는 사감에게 간다.
            log.debug("운영자 알림 문자 번호가 설정되어 있지 않아 문자 알림을 보내지 않습니다.");
            return;
        }
        String phone = PhoneNumbers.normalize(config.phone());
        if (isSupervisorPhone(phone)) {
            log.error("운영자 알림 번호가 사감 번호와 같아 문자 알림을 보내지 않습니다"
                    + "(사감 오발송 방지). phone={}", PhoneNumbers.mask(phone));
            return;
        }
        SendResult result = safeSend(() -> smsSender.send(phone, title, body));
        if (result.success()) {
            log.info("운영자 알림 문자를 보냈습니다. to={}", PhoneNumbers.mask(phone));
        } else {
            log.error("운영자 알림 문자 발송에 실패했습니다(재알림하지 않음). to={}, reason={}",
                    PhoneNumbers.mask(phone), result.errorMessage());
        }
    }

    /** 운영자 알림 번호가 사감 번호와 겹치는지 확인한다. */
    private boolean isSupervisorPhone(String normalizedPhone) {
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            return false;
        }
        for (NotificationProperties.Supervisor supervisor : properties.supervisors()) {
            if (normalizedPhone.equals(PhoneNumbers.normalize(supervisor.phone()))) {
                return true;
            }
        }
        return false;
    }

    /** 발송기가 계약을 어기고 예외를 던져도 알림 경로에서 끝낸다. */
    private static SendResult safeSend(Supplier<SendResult> action) {
        try {
            SendResult result = action.get();
            return result == null ? SendResult.fail("발송기가 결과를 반환하지 않았습니다.") : result;
        } catch (Exception ex) {
            return SendResult.fail("발송기 예외: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 문구 만들기
    // ------------------------------------------------------------------

    private static boolean isCriticalSkip(DispatchSummary summary) {
        // 등록 인원이 있는데 수신 사감이 없으면 아무도 명단을 못 받는다 = 실패와 같다.
        return summary.skipped()
                && SKIP_NO_SUPERVISOR.equals(summary.skipReason())
                && summary.targetCount() > 0;
    }

    private static String buildSubject(DispatchSummary summary, List<ChannelFailure> failures,
                                       boolean criticalSkip, String balanceWarning) {
        if (!failures.isEmpty()) {
            return "[imlate] 사감 발송 실패 " + failures.size() + "건 (" + summary.date() + ")";
        }
        if (criticalSkip) {
            return "[imlate] 사감 발송 불가 - 수신 사감 미설정 (" + summary.date() + ")";
        }
        if (balanceWarning != null) {
            return "[imlate] 문자 잔액 경고 (" + summary.date() + ")";
        }
        return "[imlate] 사감 발송 정상 (" + summary.date() + ")";
    }

    private static String buildEmailText(DispatchSummary summary, List<ChannelFailure> failures,
                                         List<ReasonGroup> groups, boolean criticalSkip,
                                         String balanceWarning) {
        StringBuilder body = new StringBuilder(512);
        body.append("기숙사 야간복귀 사감 발송 결과입니다.\n\n");
        body.append("날짜      : ").append(summary.date()).append('\n');
        body.append("대상 인원 : ").append(summary.targetCount()).append("명\n");
        if (summary.skipped()) {
            body.append("결과      : 발송하지 않음 (사유 ").append(summary.skipReason()).append(")\n");
        } else {
            body.append("문자      : 성공 ").append(summary.smsSuccess())
                    .append(" / 실패 ").append(summary.smsFailed()).append('\n');
            body.append("이메일    : 성공 ").append(summary.emailSuccess())
                    .append(" / 실패 ").append(summary.emailFailed()).append('\n');
        }

        if (criticalSkip) {
            body.append("\n[조치 필요]\n")
                    .append("등록 인원이 ").append(summary.targetCount())
                    .append("명 있는데 수신 사감이 설정되어 있지 않아 아무에게도 명단이 가지 않았습니다.\n")
                    .append("→ 조치: imlate.notification.supervisors 설정(환경변수)을 확인하세요.\n");
        }

        if (!groups.isEmpty()) {
            body.append("\n[실패 사유 요약]\n");
            for (ReasonGroup group : groups) {
                body.append("- ").append(group.reason()).append(" (").append(group.count()).append("건)\n");
                String hint = hintFor(group.reason());
                if (hint != null) {
                    body.append("  → 조치: ").append(hint).append('\n');
                }
            }
        }

        if (!failures.isEmpty()) {
            body.append("\n[실패 상세]\n");
            for (ChannelFailure failure : failures) {
                body.append("- ").append(failure.channel()).append(' ')
                        .append(failure.recipientName()).append('(').append(failure.maskedRecipient())
                        .append("): ").append(failure.reason()).append('\n');
            }
        }

        if (balanceWarning != null) {
            body.append("\n[문자 잔액]\n").append(balanceWarning).append('\n');
        }

        body.append("\n실패한 채널은 22:05 / 22:20 에 자동으로 재시도됩니다"
                + "(통금 22:30 이전). 재시도로도 복구되지 않으면 수동 발송이 필요합니다.\n");
        body.append("\n※ 이 메일은 운영자에게만 발송됩니다(사감 수신처와 분리되어 있습니다).\n");
        return body.toString();
    }

    /** 문자는 짧게. 길어지면 자동으로 LMS 로 전환되므로 핵심만 담는다. */
    private static String buildSmsBody(DispatchSummary summary, List<ReasonGroup> groups,
                                       boolean criticalSkip, String balanceWarning) {
        StringBuilder body = new StringBuilder(160);
        body.append("[imlate] ").append(summary.date()).append(' ');
        if (criticalSkip) {
            body.append("수신 사감 미설정으로 발송 불가(대상 ")
                    .append(summary.targetCount()).append("명).");
        } else if (summary.skipped()) {
            body.append("발송 건너뜀(").append(summary.skipReason()).append(").");
        } else {
            body.append("문자 성공 ").append(summary.smsSuccess()).append("/실패 ").append(summary.smsFailed())
                    .append(", 메일 성공 ").append(summary.emailSuccess())
                    .append("/실패 ").append(summary.emailFailed()).append('.');
        }
        if (!groups.isEmpty()) {
            body.append(" 주원인: ").append(groups.get(0).reason());
        }
        if (balanceWarning != null) {
            body.append(' ').append(balanceWarning);
        }
        return body.toString();
    }

    /** 아주 단순한 HTML 본문(고정폭). 메일 클라이언트에서 줄맞춤이 깨지지 않게 pre 로 감싼다. */
    private static String toHtml(String text) {
        return "<pre style=\"font-family:'Malgun Gothic',monospace;font-size:13px;\">"
                + escapeHtml(text) + "</pre>";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // ------------------------------------------------------------------
    // 실패 사유 집계
    // ------------------------------------------------------------------

    /** 같은 사유끼리 묶어 많은 순으로 최대 {@link #MAX_REASON_GROUPS} 개를 돌려준다. */
    static List<ReasonGroup> summarizeReasons(List<ChannelFailure> failures) {
        if (failures == null || failures.isEmpty()) {
            return List.of();
        }
        // LinkedHashMap: 건수가 같으면 먼저 발생한 사유가 앞에 오도록 입력 순서를 유지한다.
        Map<String, ReasonAccumulator> byReason = new LinkedHashMap<>();
        for (ChannelFailure failure : failures) {
            String key = groupingKey(failure.reason());
            byReason.computeIfAbsent(key, ignored -> new ReasonAccumulator(failure.reason())).increase();
        }
        List<ReasonGroup> groups = new ArrayList<>(byReason.size());
        for (ReasonAccumulator accumulator : byReason.values()) {
            groups.add(new ReasonGroup(accumulator.reason(), accumulator.count()));
        }
        groups.sort(Comparator.comparingInt(ReasonGroup::count).reversed());
        return groups.size() <= MAX_REASON_GROUPS ? List.copyOf(groups)
                : List.copyOf(groups.subList(0, MAX_REASON_GROUPS));
    }

    private static String groupingKey(String reason) {
        String value = reason.trim();
        return value.length() <= REASON_KEY_LENGTH ? value : value.substring(0, REASON_KEY_LENGTH);
    }

    /** 사유 문자열에서 조치 힌트를 찾는다. 없으면 null. */
    static String hintFor(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String lowered = reason.toLowerCase(Locale.ROOT);
        for (Hint hint : HINTS) {
            if (lowered.contains(hint.keyword().toLowerCase(Locale.ROOT))) {
                return hint.action();
            }
        }
        return null;
    }

    /**
     * 사유별 집계 결과.
     *
     * @param reason 대표 사유 문구
     * @param count  같은 사유 건수
     */
    record ReasonGroup(String reason, int count) {
    }

    /**
     * 사유 → 조치 힌트 한 쌍.
     *
     * @param keyword 사유 문자열에 포함되는지 볼 키워드(대소문자 무시)
     * @param action  운영자가 할 조치
     */
    private record Hint(String keyword, String action) {
    }

    /** 집계용 가변 카운터(레코드는 불변이라 별도로 둔다). */
    private static final class ReasonAccumulator {

        private final String reason;
        private int count;

        private ReasonAccumulator(String reason) {
            this.reason = reason;
        }

        private void increase() {
            count++;
        }

        private String reason() {
            return reason;
        }

        private int count() {
            return count;
        }
    }
}
