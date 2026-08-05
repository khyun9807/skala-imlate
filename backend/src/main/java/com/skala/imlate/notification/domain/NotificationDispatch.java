package com.skala.imlate.notification.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 사감 발송 이력(문자/이메일). 재시도 대상 판정과 중복 발송 방지에 사용한다.
 *
 * <p>Lombok 을 쓰지 않으므로 생성자·getter 를 직접 둔다. 외부에서는 정적 팩토리로만 만든다.
 */
@Entity
@Table(name = "notification_dispatch",
        indexes = @Index(name = "idx_notification_dispatch_date", columnList = "dispatch_date"))
public class NotificationDispatch {

    /** 채널: 문자. */
    public static final String CHANNEL_SMS = "SMS";
    /** 채널: 이메일. */
    public static final String CHANNEL_EMAIL = "EMAIL";
    /** 결과: 성공. */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 결과: 실패(재시도 대상). */
    public static final String STATUS_FAILED = "FAILED";
    /** 결과: 건너뜀(연락처 미설정 등). */
    public static final String STATUS_SKIPPED = "SKIPPED";

    private static final int MAX_RECIPIENT_NAME = 50;
    private static final int MAX_RECIPIENT = 200;
    private static final int MAX_PROVIDER_MESSAGE_ID = 200;
    private static final int MAX_ERROR_MESSAGE = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "dispatch_date", nullable = false)
    private LocalDate dispatchDate;

    @Column(name = "channel", nullable = false, length = 10)
    private String channel;

    @Column(name = "recipient_name", length = 50)
    private String recipientName;

    @Column(name = "recipient", nullable = false, length = 200)
    private String recipient;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "target_count", nullable = false)
    private int targetCount;

    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    /** JPA 전용 기본 생성자. */
    protected NotificationDispatch() {
    }

    private NotificationDispatch(LocalDate dispatchDate, String channel, String recipientName,
                                 String recipient, String status, int attempt, int targetCount,
                                 String providerMessageId, String errorMessage, LocalDateTime sentAt) {
        this.dispatchDate = dispatchDate;
        this.channel = channel;
        this.recipientName = truncate(recipientName, MAX_RECIPIENT_NAME);
        this.recipient = truncate(recipient, MAX_RECIPIENT);
        this.status = status;
        this.attempt = attempt;
        this.targetCount = targetCount;
        this.providerMessageId = truncate(providerMessageId, MAX_PROVIDER_MESSAGE_ID);
        this.errorMessage = truncate(errorMessage, MAX_ERROR_MESSAGE);
        this.sentAt = sentAt;
    }

    /** 발송 성공 이력. */
    public static NotificationDispatch success(LocalDate dispatchDate, String channel, String recipientName,
                                               String recipient, int attempt, int targetCount,
                                               String providerMessageId, LocalDateTime sentAt) {
        return new NotificationDispatch(dispatchDate, channel, recipientName, recipient,
                STATUS_SUCCESS, attempt, targetCount, providerMessageId, null, sentAt);
    }

    /** 발송 실패 이력(재시도 대상). */
    public static NotificationDispatch failed(LocalDate dispatchDate, String channel, String recipientName,
                                              String recipient, int attempt, int targetCount,
                                              String errorMessage, LocalDateTime sentAt) {
        return new NotificationDispatch(dispatchDate, channel, recipientName, recipient,
                STATUS_FAILED, attempt, targetCount, null, errorMessage, sentAt);
    }

    /** 연락처 미설정 등으로 발송하지 않은 이력. */
    public static NotificationDispatch skipped(LocalDate dispatchDate, String channel, String recipientName,
                                               String recipient, int targetCount,
                                               String reason, LocalDateTime sentAt) {
        return new NotificationDispatch(dispatchDate, channel, recipientName,
                recipient == null || recipient.isBlank() ? "(미설정)" : recipient,
                STATUS_SKIPPED, 0, targetCount, null, reason, sentAt);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public Long getId() {
        return id;
    }

    public LocalDate getDispatchDate() {
        return dispatchDate;
    }

    public String getChannel() {
        return channel;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempt() {
        return attempt;
    }

    public int getTargetCount() {
        return targetCount;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }
}
