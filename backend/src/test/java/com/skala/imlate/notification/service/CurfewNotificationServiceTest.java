package com.skala.imlate.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.skala.imlate.common.properties.NotificationProperties;
import com.skala.imlate.common.security.AccessTokenService;
import com.skala.imlate.notification.channel.EmailSender;
import com.skala.imlate.notification.channel.SendResult;
import com.skala.imlate.notification.channel.SmsSender;
import com.skala.imlate.notification.domain.NotificationDispatch;
import com.skala.imlate.notification.domain.NotificationDispatchRepository;
import com.skala.imlate.notification.ops.ChannelFailure;
import com.skala.imlate.notification.ops.DispatchHeartbeat;
import com.skala.imlate.notification.ops.DispatchHeartbeatPublisher;
import com.skala.imlate.notification.ops.OpsAlertNotifier;
import com.skala.imlate.notification.template.CurfewNoticeRenderer;
import com.skala.imlate.registration.domain.ReturnRegistration;
import com.skala.imlate.registration.service.ReconciliationService;
import com.skala.imlate.registration.service.RegistrationService;
import com.skala.imlate.stats.StatsQueryService;
import com.skala.imlate.support.TestFixtures;

/**
 * {@link CurfewNotificationService} 단위 테스트(R3, R9).
 *
 * <p>핵심 요구: <b>0명이면 발송하지 않는다</b>, 사감 수 × 2채널로 발송한다,
 * 실패하면 재시도하고 결과를 이력으로 남긴다.
 */
@DisplayName("사감 발송 서비스(CurfewNotificationService)")
class CurfewNotificationServiceTest {

    private static final NotificationProperties.Supervisor SUPERVISOR_A =
            new NotificationProperties.Supervisor("사감A", "010-1111-2222", "a@example.com");
    private static final NotificationProperties.Supervisor SUPERVISOR_B =
            new NotificationProperties.Supervisor("사감B", "010-3333-4444", "b@example.com");
    /** 전화번호만 있고 이메일이 없는 사감(EMAIL 은 SKIPPED 로 기록되어야 한다). */
    private static final NotificationProperties.Supervisor SUPERVISOR_SMS_ONLY =
            new NotificationProperties.Supervisor("사감C", "010-5555-6666", "");

    private RegistrationService registrationService;
    private ReconciliationService reconciliationService;
    private StatsQueryService statsQueryService;
    private AccessTokenService accessTokenService;
    private SmsSender smsSender;
    private EmailSender emailSender;
    private NotificationDispatchRepository dispatchRepository;
    private DispatchLockManager lockManager;
    private OpsAlertNotifier opsAlertNotifier;
    private DispatchHeartbeatPublisher heartbeatPublisher;

    @BeforeEach
    void setUp() {
        registrationService = mock(RegistrationService.class);
        reconciliationService = mock(ReconciliationService.class);
        statsQueryService = mock(StatsQueryService.class);
        accessTokenService = mock(AccessTokenService.class);
        smsSender = mock(SmsSender.class);
        emailSender = mock(EmailSender.class);
        dispatchRepository = mock(NotificationDispatchRepository.class);
        lockManager = mock(DispatchLockManager.class);
        opsAlertNotifier = mock(OpsAlertNotifier.class);
        heartbeatPublisher = mock(DispatchHeartbeatPublisher.class);

        when(accessTokenService.issue(TestFixtures.DATE)).thenReturn("TKN");
        when(reconciliationService.reconcile(TestFixtures.DATE))
                .thenReturn(TestFixtures.consistentReport(TestFixtures.DATE, 3L));
        when(statsQueryService.snapshot()).thenReturn(TestFixtures.stats());
    }

    private static NotificationProperties properties(boolean enabled, int maxAttempts,
                                                     NotificationProperties.Supervisor... supervisors) {
        return new NotificationProperties(enabled, "-", "-", maxAttempts, 60L, List.of(supervisors));
    }

    private CurfewNotificationService service(NotificationProperties properties) {
        return new CurfewNotificationService(properties, TestFixtures.imlateProperties(),
                registrationService, reconciliationService, statsQueryService, accessTokenService,
                new CurfewNoticeRenderer(), smsSender, emailSender, dispatchRepository, lockManager,
                opsAlertNotifier, heartbeatPublisher,
                TestFixtures.clockAt(LocalTime.of(22, 10)));
    }

    private void lockAcquired() {
        when(lockManager.tryAcquire(any(), anyString())).thenReturn(true);
    }

    private static List<ReturnRegistration> threeRegistrations() {
        return List.of(
                TestFixtures.registration(1L, "1", "홍길동", "302"),
                TestFixtures.registration(2L, "1", "김철수", "305"),
                TestFixtures.registration(3L, "2", "이영희", "410"));
    }

    // ------------------------------------------------------------------
    // 0명 / 건너뜀
    // ------------------------------------------------------------------

    @Test
    @DisplayName("등록 인원이 0명이면 skipped=true(NO_REGISTRATION) 이고 발송기를 전혀 호출하지 않는다")
    void 등록이_0명이면_발송하지_않는다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(List.of());

        DispatchSummary summary = service(properties(true, 3, SUPERVISOR_A, SUPERVISOR_B))
                .dispatch(TestFixtures.DATE, false);

        assertThat(summary.skipped()).isTrue();
        assertThat(summary.skipReason()).isEqualTo("NO_REGISTRATION");
        assertThat(summary.targetCount()).isZero();
        verifyNoInteractions(smsSender, emailSender);
        verify(dispatchRepository, never()).save(any(NotificationDispatch.class));
        verify(lockManager).release(eq(TestFixtures.DATE), anyString());
    }

    @Test
    @DisplayName("발송 기능이 꺼져 있으면 DISABLED 로 건너뛴다")
    void 비활성화되어_있으면_건너뛴다() {
        DispatchSummary summary = service(properties(false, 3, SUPERVISOR_A))
                .dispatch(TestFixtures.DATE, false);

        assertThat(summary.skipped()).isTrue();
        assertThat(summary.skipReason()).isEqualTo("DISABLED");
        verifyNoInteractions(lockManager, smsSender, emailSender);
    }

    @Test
    @DisplayName("다른 인스턴스가 락을 잡고 있으면 LOCK_NOT_ACQUIRED 로 건너뛴다")
    void 락을_얻지_못하면_건너뛴다() {
        when(lockManager.tryAcquire(any(), anyString())).thenReturn(false);

        DispatchSummary summary = service(properties(true, 3, SUPERVISOR_A))
                .dispatch(TestFixtures.DATE, false);

        assertThat(summary.skipped()).isTrue();
        assertThat(summary.skipReason()).isEqualTo("LOCK_NOT_ACQUIRED");
        verifyNoInteractions(smsSender, emailSender);
    }

    @Test
    @DisplayName("이미 성공 이력이 있으면 force=false 일 때 ALREADY_SENT 로 재발송하지 않는다")
    void 이미_발송했으면_재발송하지_않는다() {
        lockAcquired();
        when(dispatchRepository.countByDispatchDateAndStatus(TestFixtures.DATE,
                NotificationDispatch.STATUS_SUCCESS)).thenReturn(2L);

        DispatchSummary summary = service(properties(true, 3, SUPERVISOR_A))
                .dispatch(TestFixtures.DATE, false);

        assertThat(summary.skipped()).isTrue();
        assertThat(summary.skipReason()).isEqualTo("ALREADY_SENT");
        verifyNoInteractions(smsSender, emailSender);
    }

    @Test
    @DisplayName("수신 사감이 설정되어 있지 않으면 NO_SUPERVISOR 로 건너뛴다")
    void 수신_사감이_없으면_건너뛴다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(threeRegistrations());

        DispatchSummary summary = service(properties(true, 3)).dispatch(TestFixtures.DATE, false);

        assertThat(summary.skipped()).isTrue();
        assertThat(summary.skipReason()).isEqualTo("NO_SUPERVISOR");
        assertThat(summary.targetCount()).isEqualTo(3);
        verifyNoInteractions(smsSender, emailSender);
    }

    // ------------------------------------------------------------------
    // 정상 발송
    // ------------------------------------------------------------------

    @Test
    @DisplayName("N명이 등록되어 있으면 사감 2명 × 2채널(문자·이메일) 총 4건을 발송하고 이력을 남긴다")
    void 사감_수_곱하기_두_채널로_발송한다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(threeRegistrations());
        when(smsSender.send(anyString(), anyString(), anyString())).thenReturn(SendResult.ok("sms-id"));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SendResult.ok("mail-id"));

        DispatchSummary summary = service(properties(true, 3, SUPERVISOR_A, SUPERVISOR_B))
                .dispatch(TestFixtures.DATE, false);

        assertThat(summary.skipped()).isFalse();
        assertThat(summary.targetCount()).isEqualTo(3);
        assertThat(summary.smsSuccess()).isEqualTo(2);
        assertThat(summary.smsFailed()).isZero();
        assertThat(summary.emailSuccess()).isEqualTo(2);
        assertThat(summary.emailFailed()).isZero();
        assertThat(summary.allSucceeded()).isTrue();
        assertThat(summary.lookupUrl())
                .isEqualTo("https://imlate.example.com/lookup?date=2026-08-05&token=TKN");

        verify(smsSender, times(2)).send(anyString(), anyString(), anyString());
        verify(emailSender, times(2)).send(anyString(), anyString(), anyString(), anyString());
        verify(dispatchRepository, times(4)).save(any(NotificationDispatch.class));
        verify(lockManager).release(eq(TestFixtures.DATE), anyString());
    }

    @Test
    @DisplayName("발송 본문에 반·이름·호수와 조회 URL 이 들어가고 전화번호는 정규화된다")
    void 발송_본문과_수신처를_검증한다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(threeRegistrations());
        when(smsSender.send(anyString(), anyString(), anyString())).thenReturn(SendResult.ok("sms-id"));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SendResult.ok("mail-id"));

        service(properties(true, 3, SUPERVISOR_A)).dispatch(TestFixtures.DATE, false);

        ArgumentCaptor<String> phone = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(smsSender).send(phone.capture(), title.capture(), message.capture());

        assertThat(phone.getValue()).isEqualTo("01011112222");
        assertThat(title.getValue()).isEqualTo("[기숙사] 8/5 23:30 복귀 3명");
        assertThat(message.getValue())
                .contains("홍길동", "302", "김철수", "305", "이영희", "410", "1", "2")
                .contains("23:30", "22:30")
                .contains("https://imlate.example.com/lookup?date=2026-08-05&token=TKN");

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(to.capture(), subject.capture(), text.capture(), html.capture());

        assertThat(to.getValue()).isEqualTo("a@example.com");
        assertThat(subject.getValue()).contains("3명");
        assertThat(text.getValue()).contains("홍길동", "302");
        assertThat(html.getValue()).contains("<table");
    }

    // ------------------------------------------------------------------
    // 재시도 / 실패 이력
    // ------------------------------------------------------------------

    @Test
    @DisplayName("문자 발송이 한 번 실패하면 재시도하고, 성공하면 attempt=2 로 이력을 남긴다")
    void 실패하면_재시도한다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(threeRegistrations());
        when(smsSender.send(anyString(), anyString(), anyString()))
                .thenReturn(SendResult.fail("일시적인 오류"), SendResult.ok("sms-retry"));

        DispatchSummary summary = service(properties(true, 2, SUPERVISOR_SMS_ONLY))
                .dispatch(TestFixtures.DATE, false);

        assertThat(summary.smsSuccess()).isEqualTo(1);
        assertThat(summary.smsFailed()).isZero();
        verify(smsSender, times(2)).send(anyString(), anyString(), anyString());
        // 이메일 주소가 없으므로 EMAIL 은 발송하지 않고 SKIPPED 이력만 남긴다.
        verifyNoInteractions(emailSender);

        ArgumentCaptor<NotificationDispatch> saved = ArgumentCaptor.forClass(NotificationDispatch.class);
        verify(dispatchRepository, times(2)).save(saved.capture());

        NotificationDispatch sms = saved.getAllValues().get(0);
        assertThat(sms.getChannel()).isEqualTo(NotificationDispatch.CHANNEL_SMS);
        assertThat(sms.getStatus()).isEqualTo(NotificationDispatch.STATUS_SUCCESS);
        assertThat(sms.getAttempt()).isEqualTo(2);
        assertThat(sms.getTargetCount()).isEqualTo(3);
        assertThat(sms.getRecipient()).isEqualTo("01055556666");
        assertThat(sms.getProviderMessageId()).isEqualTo("sms-retry");

        NotificationDispatch email = saved.getAllValues().get(1);
        assertThat(email.getChannel()).isEqualTo(NotificationDispatch.CHANNEL_EMAIL);
        assertThat(email.getStatus()).isEqualTo(NotificationDispatch.STATUS_SKIPPED);
    }

    @Test
    @DisplayName("최대 시도 횟수를 모두 실패하면 FAILED 이력을 남기고 실패 건수를 보고한다")
    void 모두_실패하면_FAILED_이력을_남긴다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(threeRegistrations());
        when(smsSender.send(anyString(), anyString(), anyString())).thenReturn(SendResult.fail("문자 실패"));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SendResult.fail("메일 실패"));

        // maxAttempts=1 이면 재시도(백오프 대기) 없이 즉시 실패 처리된다.
        DispatchSummary summary = service(properties(true, 1, SUPERVISOR_A))
                .dispatch(TestFixtures.DATE, false);

        assertThat(summary.skipped()).isFalse();
        assertThat(summary.smsFailed()).isEqualTo(1);
        assertThat(summary.emailFailed()).isEqualTo(1);
        assertThat(summary.allSucceeded()).isFalse();

        ArgumentCaptor<NotificationDispatch> saved = ArgumentCaptor.forClass(NotificationDispatch.class);
        verify(dispatchRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .allSatisfy(dispatch -> assertThat(dispatch.getStatus())
                        .isEqualTo(NotificationDispatch.STATUS_FAILED));
        assertThat(saved.getAllValues().get(0).getErrorMessage()).isEqualTo("문자 실패");
        assertThat(saved.getAllValues().get(1).getErrorMessage()).isEqualTo("메일 실패");
    }

    @Test
    @DisplayName("발송기가 예외를 던져도 스케줄러가 죽지 않고 실패로 기록된다")
    void 발송기_예외도_실패로_처리된다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(threeRegistrations());
        when(smsSender.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("provider down"));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SendResult.ok("mail-id"));

        DispatchSummary summary = service(properties(true, 1, SUPERVISOR_A))
                .dispatch(TestFixtures.DATE, false);

        assertThat(summary.smsFailed()).isEqualTo(1);
        assertThat(summary.emailSuccess()).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도할 실패 이력이 없으면 NO_FAILURE 로 건너뛴다")
    void 재시도할_실패가_없으면_건너뛴다() {
        when(dispatchRepository.findByDispatchDate(TestFixtures.DATE)).thenReturn(List.of());

        DispatchSummary summary = service(properties(true, 3, SUPERVISOR_A))
                .retryFailed(TestFixtures.DATE);

        assertThat(summary.skipped()).isTrue();
        assertThat(summary.skipReason()).isEqualTo("NO_FAILURE");
        verifyNoInteractions(smsSender, emailSender);
    }

    @Test
    @DisplayName("preview 는 실제 발송 없이 렌더 결과만 만든다(대사는 복구 없이 조회만)")
    void 미리보기는_발송하지_않는다() {
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(threeRegistrations());
        when(reconciliationService.inspect(TestFixtures.DATE))
                .thenReturn(TestFixtures.consistentReport(TestFixtures.DATE, 3L));

        CurfewNotificationService.NoticePreview preview =
                service(properties(true, 3, SUPERVISOR_A)).preview(TestFixtures.DATE);

        assertThat(preview.date()).isEqualTo(TestFixtures.DATE);
        assertThat(preview.targetCount()).isEqualTo(3);
        assertThat(preview.smsTitle()).isEqualTo("[기숙사] 8/5 23:30 복귀 3명");
        assertThat(preview.smsBody()).contains("홍길동");
        assertThat(preview.emailHtml()).contains("<table");
        assertThat(preview.lookupUrl())
                .isEqualTo("https://imlate.example.com/lookup?date=2026-08-05&token=TKN");

        verifyNoInteractions(smsSender, emailSender, lockManager);
        verify(reconciliationService, never()).reconcile(any());
        // 미리보기는 발송 시도가 아니므로 하트비트도 알림도 남기지 않는다.
        verifyNoInteractions(heartbeatPublisher, opsAlertNotifier);
    }

    // ------------------------------------------------------------------
    // 운영자 알림 / 하트비트 (위험 2 대응)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("최종 실패한 채널의 사유가 운영자 알림으로 그대로 전달된다")
    void 실패_사유가_운영자_알림으로_전달된다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(threeRegistrations());
        when(smsSender.send(anyString(), anyString(), anyString()))
                .thenReturn(SendResult.fail("Aligo 발송 실패(result_code=-101): 인증 오류"));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SendResult.ok("mail-id"));

        DispatchSummary summary = service(properties(true, 1, SUPERVISOR_A))
                .dispatch(TestFixtures.DATE, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChannelFailure>> failures = ArgumentCaptor.forClass(List.class);
        verify(opsAlertNotifier).notifyDispatchResult(eq(summary), failures.capture());

        assertThat(failures.getValue()).hasSize(1);
        ChannelFailure failure = failures.getValue().get(0);
        assertThat(failure.channel()).isEqualTo(NotificationDispatch.CHANNEL_SMS);
        assertThat(failure.recipientName()).isEqualTo("사감A");
        assertThat(failure.reason()).contains("result_code=-101");
        // 수신처는 마스킹된 값만 넘긴다(알림 문구와 로그에 그대로 실리기 때문).
        assertThat(failure.maskedRecipient()).isEqualTo("010****2222");
    }

    @Test
    @DisplayName("모두 성공하면 실패 목록이 빈 채로 알림 담당에게 전달된다(보낼지 말지는 알림 담당이 판단)")
    void 성공하면_빈_실패목록이_전달된다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(threeRegistrations());
        when(smsSender.send(anyString(), anyString(), anyString())).thenReturn(SendResult.ok("sms-id"));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SendResult.ok("mail-id"));

        service(properties(true, 1, SUPERVISOR_A)).dispatch(TestFixtures.DATE, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChannelFailure>> failures = ArgumentCaptor.forClass(List.class);
        verify(opsAlertNotifier).notifyDispatchResult(any(DispatchSummary.class), failures.capture());
        assertThat(failures.getValue()).isEmpty();
    }

    @Test
    @DisplayName("운영자 알림이 예외를 던져도 발송 결과는 그대로 반환된다(부작용 금지)")
    void 알림이_실패해도_발송_결과는_그대로다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(threeRegistrations());
        when(smsSender.send(anyString(), anyString(), anyString())).thenReturn(SendResult.ok("sms-id"));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SendResult.ok("mail-id"));
        doThrow(new IllegalStateException("알림 시스템 장애"))
                .when(opsAlertNotifier).notifyDispatchResult(any(), any());

        DispatchSummary summary = service(properties(true, 1, SUPERVISOR_A))
                .dispatch(TestFixtures.DATE, false);

        assertThat(summary.skipped()).isFalse();
        assertThat(summary.smsSuccess()).isEqualTo(1);
        assertThat(summary.emailSuccess()).isEqualTo(1);
        assertThat(summary.allSucceeded()).isTrue();
    }

    @Test
    @DisplayName("하트비트가 예외를 던져도 발송 결과가 그대로 반환되고 운영자 알림은 계속 수행된다")
    void 하트비트가_실패해도_발송_결과는_그대로다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(threeRegistrations());
        when(smsSender.send(anyString(), anyString(), anyString())).thenReturn(SendResult.ok("sms-id"));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SendResult.ok("mail-id"));
        doThrow(new IllegalStateException("지표 전송 실패"))
                .when(heartbeatPublisher).publish(any());

        DispatchSummary summary = service(properties(true, 1, SUPERVISOR_A))
                .dispatch(TestFixtures.DATE, false);

        assertThat(summary.allSucceeded()).isTrue();
        verify(opsAlertNotifier).notifyDispatchResult(any(DispatchSummary.class), any());
    }

    @Test
    @DisplayName("발송했으면 result=SENT 하트비트를 실패 건수와 함께 남긴다")
    void 발송하면_하트비트를_남긴다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(threeRegistrations());
        when(smsSender.send(anyString(), anyString(), anyString())).thenReturn(SendResult.fail("문자 실패"));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SendResult.ok("mail-id"));

        service(properties(true, 1, SUPERVISOR_A)).dispatch(TestFixtures.DATE, false);

        ArgumentCaptor<DispatchHeartbeat> heartbeat = ArgumentCaptor.forClass(DispatchHeartbeat.class);
        verify(heartbeatPublisher).publish(heartbeat.capture());

        assertThat(heartbeat.getValue().kind()).isEqualTo(DispatchHeartbeat.Kind.DISPATCH);
        assertThat(heartbeat.getValue().date()).isEqualTo(TestFixtures.DATE);
        assertThat(heartbeat.getValue().result()).isEqualTo("SENT");
        assertThat(heartbeat.getValue().targetCount()).isEqualTo(3);
        assertThat(heartbeat.getValue().failureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("건너뛴 경우에도 하트비트는 남긴다 — 21:50 에 아무 신호도 없는 것과 구분되어야 한다")
    void 건너뛰어도_하트비트는_남긴다() {
        lockAcquired();
        when(registrationService.findByDate(TestFixtures.DATE)).thenReturn(List.of());

        service(properties(true, 1, SUPERVISOR_A)).dispatch(TestFixtures.DATE, false);

        ArgumentCaptor<DispatchHeartbeat> heartbeat = ArgumentCaptor.forClass(DispatchHeartbeat.class);
        verify(heartbeatPublisher).publish(heartbeat.capture());

        assertThat(heartbeat.getValue().result()).isEqualTo("SKIPPED:NO_REGISTRATION");
        assertThat(heartbeat.getValue().failureCount()).isZero();
    }

    @Test
    @DisplayName("발송이 비활성화되어 있어도 하트비트는 남긴다(스케줄러가 살아 있음을 알린다)")
    void 비활성화_상태에서도_하트비트는_남긴다() {
        service(properties(false, 1, SUPERVISOR_A)).dispatch(TestFixtures.DATE, false);

        ArgumentCaptor<DispatchHeartbeat> heartbeat = ArgumentCaptor.forClass(DispatchHeartbeat.class);
        verify(heartbeatPublisher).publish(heartbeat.capture());
        assertThat(heartbeat.getValue().result()).isEqualTo("SKIPPED:DISABLED");
    }

    @Test
    @DisplayName("재시도 실행의 하트비트는 kind=RETRY 로 구분된다")
    void 재시도_하트비트는_RETRY다() {
        when(dispatchRepository.findByDispatchDate(TestFixtures.DATE)).thenReturn(List.of());

        service(properties(true, 1, SUPERVISOR_A)).retryFailed(TestFixtures.DATE);

        ArgumentCaptor<DispatchHeartbeat> heartbeat = ArgumentCaptor.forClass(DispatchHeartbeat.class);
        verify(heartbeatPublisher).publish(heartbeat.capture());
        assertThat(heartbeat.getValue().kind()).isEqualTo(DispatchHeartbeat.Kind.RETRY);
        assertThat(heartbeat.getValue().result()).isEqualTo("SKIPPED:NO_FAILURE");
    }
}
