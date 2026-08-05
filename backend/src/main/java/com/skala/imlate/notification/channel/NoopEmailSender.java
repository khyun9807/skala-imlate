package com.skala.imlate.notification.channel;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 실제 발송 없이 로그만 남기는 이메일 발송기(로컬·테스트 기본값).
 *
 * <p>{@code imlate.email.provider} 가 없거나 {@code noop} 이면 이 구현이 사용된다.
 * HTML 본문은 길어서 로그를 덮으므로 길이만 남기고, 텍스트 본문은 전문을 남긴다.
 */
@Component
@ConditionalOnProperty(prefix = "imlate.email", name = "provider", havingValue = "noop", matchIfMissing = true)
public class NoopEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(NoopEmailSender.class);

    @Override
    public SendResult send(String toEmail, String subject, String textBody, String htmlBody) {
        String messageId = "noop-email-" + UUID.randomUUID();
        log.info("""
                [NOOP-EMAIL] 실제 발송하지 않고 로그만 남깁니다.
                  수신처   : {}
                  제목     : {}
                  HTML길이 : {}자
                  텍스트본문:
                {}""", PhoneNumbers.maskEmail(toEmail), subject,
                htmlBody == null ? 0 : htmlBody.length(), textBody);
        return SendResult.ok(messageId);
    }

    @Override
    public String providerName() {
        return "noop";
    }
}
