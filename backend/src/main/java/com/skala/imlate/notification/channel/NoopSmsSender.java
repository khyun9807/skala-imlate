package com.skala.imlate.notification.channel;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 실제 발송 없이 로그만 남기는 문자 발송기(로컬·테스트 기본값).
 *
 * <p>{@code imlate.sms.provider} 가 없거나 {@code noop} 이면 이 구현이 사용된다.
 * 로컬에서 문구를 눈으로 확인할 수 있도록 제목·본문을 INFO 로 남긴다.
 */
@Component
@ConditionalOnProperty(prefix = "imlate.sms", name = "provider", havingValue = "noop", matchIfMissing = true)
public class NoopSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(NoopSmsSender.class);

    @Override
    public SendResult send(String toPhone, String title, String message) {
        String messageId = "noop-sms-" + UUID.randomUUID();
        log.info("""
                [NOOP-SMS] 실제 발송하지 않고 로그만 남깁니다.
                  수신번호: {}
                  제목    : {}
                  본문    :
                {}""", PhoneNumbers.mask(toPhone), title, message);
        return SendResult.ok(messageId);
    }

    @Override
    public String providerName() {
        return "noop";
    }
}
