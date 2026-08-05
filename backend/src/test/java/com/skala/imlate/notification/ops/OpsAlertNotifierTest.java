package com.skala.imlate.notification.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.skala.imlate.common.properties.NotificationProperties;
import com.skala.imlate.notification.channel.EmailSender;
import com.skala.imlate.notification.channel.SendResult;
import com.skala.imlate.notification.channel.SmsSender;
import com.skala.imlate.notification.service.DispatchSummary;
import com.skala.imlate.support.TestFixtures;

/**
 * {@link OpsAlertNotifier} 단위 테스트(위험 2·3).
 *
 * <p>핵심 요구: <b>실패하면 운영자가 알게 된다</b>, <b>성공은 조용하다</b>(알림 피로 방지),
 * 그리고 무엇보다 <b>운영 알림이 사감에게 가지 않는다</b>.
 */
@DisplayName("운영자 알림(OpsAlertNotifier)")
class OpsAlertNotifierTest {

    private static final NotificationProperties.Supervisor SUPERVISOR =
            new NotificationProperties.Supervisor("사감A", "010-1111-2222", "supervisor@example.com");

    private static final String OPS_EMAIL = "ops@example.com";
    private static final String OPS_PHONE = "010-9999-8888";

    private SmsSender smsSender;
    private EmailSender emailSender;
    private SmsBalanceMonitor balanceMonitor;

    @BeforeEach
    void setUp() {
        smsSender = mock(SmsSender.class);
        emailSender = mock(EmailSender.class);
        balanceMonitor = mock(SmsBalanceMonitor.class);

        when(smsSender.send(anyString(), anyString(), anyString())).thenReturn(SendResult.ok("sms-id"));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SendResult.ok("mail-id"));
        when(balanceMonitor.checkOnce(any())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------
    // 픽스처
    // ------------------------------------------------------------------

    private OpsAlertNotifier notifier(NotificationProperties.OpsAlert opsAlert) {
        NotificationProperties properties = new NotificationProperties(
                true, "-", "-", 1, 60L, List.of(SUPERVISOR), opsAlert);
        return new OpsAlertNotifier(properties, smsSender, emailSender, balanceMonitor);
    }

    private static NotificationProperties.OpsAlert opsAlert(String phone, boolean notifyOnSuccess) {
        return new NotificationProperties.OpsAlert(true, OPS_EMAIL, phone, notifyOnSuccess);
    }

    /** 문자 1건이 최종 실패한 결과. */
    private static DispatchSummary failedSummary() {
        return DispatchSummary.ofSent(TestFixtures.DATE, 3, 0, 1, 1, 0, "https://imlate.example.com/lookup");
    }

    private static DispatchSummary successSummary() {
        return DispatchSummary.ofSent(TestFixtures.DATE, 3, 1, 0, 1, 0, "https://imlate.example.com/lookup");
    }

    private static ChannelFailure aligoIpFailure() {
        return new ChannelFailure("SMS", "사감A", "010****2222",
                "Aligo 발송 실패(result_code=-101): 인증 오류입니다.");
    }

    private String capturedEmailText() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(anyString(), anyString(), text.capture(), anyString());
        return text.getValue();
    }

    // ------------------------------------------------------------------
    // 알릴 때 / 알리지 않을 때
    // ------------------------------------------------------------------

    @Test
    @DisplayName("실패가 하나라도 있으면 운영자에게 메일로 알린다")
    void 실패하면_운영자에게_알린다() {
        notifier(opsAlert("", false)).notifyDispatchResult(failedSummary(), List.of(aligoIpFailure()));

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(to.capture(), subject.capture(), anyString(), anyString());

        assertThat(to.getValue()).isEqualTo(OPS_EMAIL);
        assertThat(subject.getValue()).contains("실패", "1건", "2026-08-05");
    }

    @Test
    @DisplayName("전부 성공하면 알리지 않는다(notify-on-success=false — 매일 오는 알림은 무시하게 된다)")
    void 성공하면_알리지_않는다() {
        notifier(opsAlert("", false)).notifyDispatchResult(successSummary(), List.of());

        verifyNoInteractions(emailSender, smsSender);
    }

    @Test
    @DisplayName("notify-on-success=true 면 전부 성공해도 알린다")
    void 성공_알림을_켜면_성공에도_알린다() {
        notifier(opsAlert("", true)).notifyDispatchResult(successSummary(), List.of());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(anyString(), subject.capture(), anyString(), anyString());
        assertThat(subject.getValue()).contains("정상");
    }

    @Test
    @DisplayName("ops-alert.enabled=false 면 아무 알림도 보내지 않는다")
    void 비활성화하면_알리지_않는다() {
        NotificationProperties.OpsAlert disabled =
                new NotificationProperties.OpsAlert(false, OPS_EMAIL, OPS_PHONE, true);

        notifier(disabled).notifyDispatchResult(failedSummary(), List.of(aligoIpFailure()));

        verifyNoInteractions(emailSender, smsSender);
    }

    @Test
    @DisplayName("등록 인원이 있는데 수신 사감이 없으면(NO_SUPERVISOR) 실패와 같이 취급해 알린다")
    void 수신_사감이_없으면_알린다() {
        DispatchSummary summary = DispatchSummary.ofSkipped(TestFixtures.DATE, "NO_SUPERVISOR", 3);

        notifier(opsAlert("", false)).notifyDispatchResult(summary, List.of());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(anyString(), subject.capture(), anyString(), anyString());
        assertThat(subject.getValue()).contains("수신 사감 미설정");
    }

    @Test
    @DisplayName("등록 인원이 0명이라 건너뛴 날은 알리지 않는다(정상 상황이다)")
    void 등록이_0명인_날은_알리지_않는다() {
        DispatchSummary summary = DispatchSummary.ofSkipped(TestFixtures.DATE, "NO_REGISTRATION");

        notifier(opsAlert("", false)).notifyDispatchResult(summary, List.of());

        verifyNoInteractions(emailSender, smsSender);
    }

    // ------------------------------------------------------------------
    // 사감 오발송 방지 (가장 중요한 제약)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ops-alert.phone 이 비어 있으면 문자 알림을 시도하지 않는다(사감 오발송 방지)")
    void 번호가_비면_문자를_보내지_않는다() {
        notifier(opsAlert("", false)).notifyDispatchResult(failedSummary(), List.of(aligoIpFailure()));

        verify(emailSender).send(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(smsSender);
    }

    @Test
    @DisplayName("ops-alert.phone 이 사감 번호와 같으면 문자 알림을 보내지 않는다")
    void 사감_번호와_같으면_문자를_보내지_않는다() {
        // 하이픈 유무가 달라도 같은 번호로 판정해야 한다.
        notifier(opsAlert("01011112222", false))
                .notifyDispatchResult(failedSummary(), List.of(aligoIpFailure()));

        verify(emailSender).send(anyString(), anyString(), anyString(), anyString());
        verify(smsSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("사감과 다른 운영자 번호가 설정되어 있으면 문자로도 알린다")
    void 운영자_번호가_있으면_문자로도_알린다() {
        notifier(opsAlert(OPS_PHONE, false)).notifyDispatchResult(failedSummary(), List.of(aligoIpFailure()));

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(smsSender).send(to.capture(), anyString(), body.capture());

        assertThat(to.getValue()).isEqualTo("01099998888");
        assertThat(body.getValue()).contains("[imlate]", "2026-08-05");
    }

    // ------------------------------------------------------------------
    // 부작용 금지 / 무한 루프 금지
    // ------------------------------------------------------------------

    @Test
    @DisplayName("알림 메일 발송기가 예외를 던져도 예외를 밖으로 전파하지 않는다")
    void 알림_발송기_예외를_흡수한다() {
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("SES 장애"));

        assertThatCode(() -> notifier(opsAlert(OPS_PHONE, false))
                .notifyDispatchResult(failedSummary(), List.of(aligoIpFailure())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("알림 메일이 실패해도 그 실패로 다시 알리지 않는다(무한 루프 방지)")
    void 알림_실패로_다시_알리지_않는다() {
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SendResult.fail("SES 거부"));

        notifier(opsAlert(OPS_PHONE, false)).notifyDispatchResult(failedSummary(), List.of(aligoIpFailure()));

        // 메일 1회, 문자 1회. 실패를 사유로 한 추가 발송이 없어야 한다.
        verify(emailSender).send(anyString(), anyString(), anyString(), anyString());
        verify(smsSender).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("요약이 null 이면 아무것도 하지 않는다")
    void 요약이_없으면_아무것도_하지_않는다() {
        assertThatCode(() -> notifier(opsAlert(OPS_PHONE, true)).notifyDispatchResult(null, null))
                .doesNotThrowAnyException();
        verifyNoInteractions(emailSender, smsSender);
    }

    // ------------------------------------------------------------------
    // 본문 — 실패 사유 요약과 조치 힌트
    // ------------------------------------------------------------------

    @Test
    @DisplayName("본문에 날짜·인원·채널별 성공/실패 건수와 실패 사유·조치 힌트가 들어간다")
    void 본문에_요약과_조치_힌트가_들어간다() {
        notifier(opsAlert("", false)).notifyDispatchResult(failedSummary(), List.of(aligoIpFailure()));

        assertThat(capturedEmailText())
                .contains("2026-08-05")
                .contains("3명")
                .contains("성공 0 / 실패 1")
                .contains("result_code=-101")
                .contains("허용 IP");
    }

    @Test
    @DisplayName("SES 미검증 실패에는 검증 상태 확인 힌트가 붙는다")
    void SES_미검증_힌트를_붙인다() {
        ChannelFailure sesFailure = new ChannelFailure("EMAIL", "사감A", "s*********@example.com",
                "Email address is not verified. The following identities failed the check");

        notifier(opsAlert("", false)).notifyDispatchResult(failedSummary(), List.of(sesFailure));

        assertThat(capturedEmailText()).contains("SES", "검증");
    }

    @Test
    @DisplayName("같은 사유가 여러 건이면 건수와 함께 묶어 가장 흔한 원인부터 최대 2개만 보여준다")
    void 사유를_묶어_가장_흔한_것부터_보여준다() {
        List<ChannelFailure> failures = List.of(
                new ChannelFailure("SMS", "사감A", "010****2222", "원인 A"),
                new ChannelFailure("EMAIL", "사감A", "a***@example.com", "원인 B"),
                new ChannelFailure("SMS", "사감B", "010****4444", "원인 B"),
                new ChannelFailure("EMAIL", "사감B", "b***@example.com", "원인 C"));

        List<OpsAlertNotifier.ReasonGroup> groups = OpsAlertNotifier.summarizeReasons(failures);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).reason()).isEqualTo("원인 B");
        assertThat(groups.get(0).count()).isEqualTo(2);
        assertThat(groups.get(1).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("알 수 없는 사유에는 조치 힌트를 붙이지 않는다(추측으로 오도하지 않는다)")
    void 모르는_사유에는_힌트가_없다() {
        assertThat(OpsAlertNotifier.hintFor("알 수 없는 오류")).isNull();
        assertThat(OpsAlertNotifier.hintFor(null)).isNull();
    }

    // ------------------------------------------------------------------
    // 잔액 경고 (위험 3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("잔액이 임계값 미만이면 전부 성공한 날에도 알리고 본문에 잔액 문구가 들어간다")
    void 잔액이_부족하면_성공한_날에도_알린다() {
        when(balanceMonitor.checkOnce(TestFixtures.DATE))
                .thenReturn(Optional.of("문자 잔여 건수 부족: SMS 12건 / LMS 3건 (임계값 100건)"));

        notifier(opsAlert("", false)).notifyDispatchResult(successSummary(), List.of());

        assertThat(capturedEmailText()).contains("문자 잔액", "SMS 12건");
    }

    @Test
    @DisplayName("건너뛴 날은 문자를 보내지 않았으므로 잔액을 조회하지 않는다")
    void 건너뛴_날은_잔액을_조회하지_않는다() {
        DispatchSummary summary = DispatchSummary.ofSkipped(TestFixtures.DATE, "NO_REGISTRATION");

        notifier(opsAlert("", false)).notifyDispatchResult(summary, List.of());

        verifyNoInteractions(balanceMonitor);
    }

    // ------------------------------------------------------------------
    // 수신처 기본값
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ops-alert.email 이 비어 있으면 contact-email 로 보낸다(사감 메일 목록과 무관하다)")
    void 메일이_비면_문의처_메일로_보낸다() {
        NotificationProperties properties = new NotificationProperties(
                true, "-", "-", 1, 60L, "SKALA 운영진", "contact@example.com",
                List.of(SUPERVISOR),
                new NotificationProperties.OpsAlert(true, "", "", false), null);
        OpsAlertNotifier notifier =
                new OpsAlertNotifier(properties, smsSender, emailSender, balanceMonitor);

        notifier.notifyDispatchResult(failedSummary(), List.of(aligoIpFailure()));

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(to.capture(), anyString(), anyString(), anyString());
        assertThat(to.getValue()).isEqualTo("contact@example.com");
        // 사감 메일 주소로는 절대 보내지 않는다.
        assertThat(to.getValue()).isNotEqualTo(SUPERVISOR.email());
    }
}
