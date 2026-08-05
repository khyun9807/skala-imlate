package com.skala.imlate.notification.channel;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.EmailProperties;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

/**
 * Amazon SES v2 기반 이메일 발송기({@code imlate.email.provider=ses}).
 *
 * <p>제목·본문 모두 UTF-8 로 지정해 한글이 깨지지 않게 하고, 발신자 표시 이름이 비ASCII(한글)이면
 * RFC 2047 Base64 인코딩(`=?UTF-8?B?...?=`)으로 감싼다. SDK 예외는 모두 잡아
 * {@link SendResult#fail(String)} 으로 돌려주고 <b>예외를 전파하지 않는다.</b>
 */
@Component
@ConditionalOnProperty(prefix = "imlate.email", name = "provider", havingValue = "ses")
public class SesEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);
    private static final String CHARSET = "UTF-8";

    private final SesV2Client sesClient;
    private final EmailProperties properties;

    public SesEmailSender(SesV2Client sesClient, EmailProperties properties) {
        this.sesClient = sesClient;
        this.properties = properties;
    }

    @Override
    public SendResult send(String toEmail, String subject, String textBody, String htmlBody) {
        EmailProperties.Ses config = properties.ses();
        if (toEmail == null || toEmail.isBlank()) {
            return SendResult.fail("수신 이메일 주소가 비어 있습니다.");
        }
        if (config.from().isBlank()) {
            return SendResult.fail("SES 발신 주소(imlate.email.ses.from)가 설정되지 않았습니다.");
        }

        try {
            Body body = Body.builder()
                    .text(Content.builder().data(nullToEmpty(textBody)).charset(CHARSET).build())
                    .html(Content.builder().data(nullToEmpty(htmlBody)).charset(CHARSET).build())
                    .build();
            Message message = Message.builder()
                    .subject(Content.builder().data(nullToEmpty(subject)).charset(CHARSET).build())
                    .body(body)
                    .build();

            SendEmailRequest.Builder request = SendEmailRequest.builder()
                    .fromEmailAddress(formatFromAddress(config))
                    .destination(Destination.builder().toAddresses(toEmail.trim()).build())
                    .content(EmailContent.builder().simple(message).build());
            // configuration set 은 값이 있을 때만 지정한다(없는 이름을 넘기면 SES 가 거부한다).
            if (!config.configurationSet().isBlank()) {
                request.configurationSetName(config.configurationSet());
            }

            SendEmailResponse response = sesClient.sendEmail(request.build());
            log.info("SES 이메일 발송 성공: to={}, messageId={}",
                    PhoneNumbers.maskEmail(toEmail), response.messageId());
            return SendResult.ok(response.messageId());
        } catch (Exception ex) {
            // 자격증명·검증 실패·throttling 등 모든 예외를 흡수한다.
            log.warn("SES 이메일 발송 실패: to={}, cause={}", PhoneNumbers.maskEmail(toEmail), ex.toString());
            return SendResult.fail("SES 발송 실패: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
        }
    }

    @Override
    public String providerName() {
        return "ses";
    }

    /** {@code 표시이름 <메일주소>} 형태로 조립한다. 표시 이름이 한글이면 RFC 2047 로 인코딩한다. */
    private static String formatFromAddress(EmailProperties.Ses config) {
        String from = config.from().trim();
        String displayName = config.fromName() == null ? "" : config.fromName().trim();
        if (displayName.isEmpty()) {
            return from;
        }
        return encodeDisplayName(displayName) + " <" + from + ">";
    }

    /** 비ASCII 문자가 있으면 RFC 2047 Base64(=?UTF-8?B?..?=)로, 아니면 큰따옴표로 감싼다. */
    private static String encodeDisplayName(String displayName) {
        if (isAscii(displayName)) {
            return "\"" + displayName.replace("\\", "").replace("\"", "") + "\"";
        }
        String encoded = Base64.getEncoder()
                .encodeToString(displayName.getBytes(StandardCharsets.UTF_8));
        return "=?UTF-8?B?" + encoded + "?=";
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
