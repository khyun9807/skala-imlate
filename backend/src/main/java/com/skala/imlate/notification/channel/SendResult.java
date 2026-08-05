package com.skala.imlate.notification.channel;

/**
 * 외부 발송(문자·이메일) 결과. 발송 실패는 예외가 아니라 이 값으로 전달한다.
 *
 * @param success           발송 성공 여부
 * @param providerMessageId 제공자가 돌려준 메시지 식별자(없으면 null)
 * @param errorMessage      실패 사유(성공이면 null)
 */
public record SendResult(boolean success, String providerMessageId, String errorMessage) {

    /** 성공 결과. */
    public static SendResult ok(String id) {
        return new SendResult(true, id, null);
    }

    /** 실패 결과. */
    public static SendResult fail(String message) {
        return new SendResult(false, null, message == null ? "알 수 없는 오류" : message);
    }
}
