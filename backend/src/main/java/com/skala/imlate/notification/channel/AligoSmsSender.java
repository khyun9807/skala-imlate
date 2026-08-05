package com.skala.imlate.notification.channel;

import java.nio.charset.Charset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.imlate.common.properties.SmsProperties;

/**
 * Aligo REST API 기반 문자 발송기({@code imlate.sms.provider=aligo}).
 *
 * <p>{@code application/x-www-form-urlencoded} 로 POST 하며, 본문 길이(EUC-KR 기준 바이트)가
 * 90바이트를 넘으면 자동으로 LMS 로 전환한다. 네트워크·타임아웃·파싱 오류는 모두 잡아
 * {@link SendResult#fail(String)} 으로 돌려주고 <b>예외를 전파하지 않는다.</b>
 */
@Component
@ConditionalOnProperty(prefix = "imlate.sms", name = "provider", havingValue = "aligo")
public class AligoSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(AligoSmsSender.class);

    /** 국내 이동통신 SMS 길이 판정 기준 문자셋. */
    private static final Charset EUC_KR = Charset.forName("EUC-KR");
    /** 이 바이트 수를 넘으면 LMS 로 전환한다. */
    private static final int SMS_MAX_BYTES = 90;
    /** LMS 본문 최대 바이트. */
    private static final int LMS_MAX_BYTES = 2000;
    /** LMS 제목 최대 바이트. */
    private static final int LMS_TITLE_MAX_BYTES = 44;
    /** 본문이 잘렸음을 알리는 꼬리말. */
    private static final String TRUNCATION_MARK = "…(생략)";

    private final RestClient restClient;
    private final SmsProperties properties;
    private final ObjectMapper objectMapper;

    public AligoSmsSender(@Qualifier("aligoRestClient") RestClient restClient,
                          SmsProperties properties,
                          @Qualifier("imlateObjectMapper") ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public SendResult send(String toPhone, String title, String message) {
        SmsProperties.Aligo config = properties.aligo();

        String receiver = PhoneNumbers.normalize(toPhone);
        if (!PhoneNumbers.isValid(receiver)) {
            return SendResult.fail("수신 번호 형식이 올바르지 않습니다: " + PhoneNumbers.mask(toPhone));
        }
        String sender = PhoneNumbers.normalize(config.sender());
        if (config.apiKey().isBlank() || config.userId().isBlank() || sender.isBlank()) {
            return SendResult.fail("Aligo 설정(api-key / user-id / sender)이 비어 있어 발송할 수 없습니다.");
        }

        String body = message == null ? "" : message;
        boolean lms = byteLength(body) > SMS_MAX_BYTES;
        if (lms) {
            body = capToLmsLimit(body);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("key", config.apiKey());
        form.add("user_id", config.userId());
        form.add("sender", sender);
        form.add("receiver", receiver);
        form.add("msg", body);
        form.add("msg_type", lms ? "LMS" : "SMS");
        // LMS 는 제목이 필수다. 44바이트를 넘으면 잘라서 보낸다.
        form.add("title", truncateToBytes(blankToDefault(title), LMS_TITLE_MAX_BYTES));
        form.add("testmode_yn", config.testMode() ? "Y" : "N");

        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(config.apiUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    // 4xx/5xx 라도 예외를 던지지 않고 본문 내용으로 판정한다.
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> { })
                    .toEntity(String.class);

            SendResult result = parse(response.getBody(), response.getStatusCode());
            if (result.success()) {
                log.info("Aligo 문자 발송 성공: to={}, type={}, msgId={}",
                        PhoneNumbers.mask(receiver), lms ? "LMS" : "SMS", result.providerMessageId());
            } else {
                log.warn("Aligo 문자 발송 실패: to={}, type={}, reason={}",
                        PhoneNumbers.mask(receiver), lms ? "LMS" : "SMS", result.errorMessage());
            }
            return result;
        } catch (Exception ex) {
            // 타임아웃·연결 실패 등 모든 예외를 흡수한다(서비스 가용성 우선).
            log.warn("Aligo 문자 발송 중 오류: to={}, cause={}", PhoneNumbers.mask(receiver), ex.toString());
            return SendResult.fail("Aligo 호출 실패: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
        }
    }

    @Override
    public String providerName() {
        return "aligo";
    }

    /** Aligo 응답 JSON 을 해석한다. {@code result_code} 가 1(문자열/숫자 무관)이면 성공. */
    private SendResult parse(String rawBody, HttpStatusCode status) {
        if (rawBody == null || rawBody.isBlank()) {
            return SendResult.fail("Aligo 응답이 비어 있습니다. httpStatus=" + status.value());
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            log.debug("Aligo 응답 JSON 파싱 실패: {}", ex.toString());
            return SendResult.fail("Aligo 응답을 해석할 수 없습니다. httpStatus=" + status.value()
                    + ", body=" + abbreviate(rawBody));
        }
        // 문자열 "1" 과 숫자 1 을 모두 허용한다.
        int resultCode = root.path("result_code").asInt(Integer.MIN_VALUE);
        String providerMessage = root.path("message").asText("");
        if (resultCode == 1) {
            String messageId = root.path("msg_id").asText("");
            return SendResult.ok(messageId.isBlank() ? null : messageId);
        }
        String code = resultCode == Integer.MIN_VALUE ? "없음" : String.valueOf(resultCode);
        return SendResult.fail("Aligo 발송 실패(result_code=" + code + "): "
                + (providerMessage.isBlank() ? abbreviate(rawBody) : providerMessage));
    }

    /** LMS 최대 바이트를 넘으면 안전하게 잘라내고 생략 표기를 붙인다. */
    private static String capToLmsLimit(String body) {
        if (byteLength(body) <= LMS_MAX_BYTES) {
            return body;
        }
        int room = LMS_MAX_BYTES - byteLength(TRUNCATION_MARK);
        return truncateToBytes(body, Math.max(0, room)) + TRUNCATION_MARK;
    }

    /** EUC-KR 기준 바이트 길이. */
    private static int byteLength(String value) {
        return value == null ? 0 : value.getBytes(EUC_KR).length;
    }

    /** 코드포인트 경계를 지키면서 EUC-KR 기준 maxBytes 이내로 자른다. */
    private static String truncateToBytes(String source, int maxBytes) {
        if (source == null || source.isEmpty() || maxBytes <= 0) {
            return "";
        }
        if (byteLength(source) <= maxBytes) {
            return source;
        }
        StringBuilder builder = new StringBuilder();
        int used = 0;
        int index = 0;
        while (index < source.length()) {
            int codePoint = source.codePointAt(index);
            int charCount = Character.charCount(codePoint);
            String piece = source.substring(index, index + charCount);
            int width = byteLength(piece);
            if (used + width > maxBytes) {
                break;
            }
            builder.append(piece);
            used += width;
            index += charCount;
        }
        return builder.toString();
    }

    private static String blankToDefault(String title) {
        return title == null || title.isBlank() ? "기숙사 야간복귀 명단" : title;
    }

    private static String abbreviate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
