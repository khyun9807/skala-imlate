package com.skala.imlate.notification.ops;

/**
 * 최종 실패한 발송 1건(채널 × 수신처).
 *
 * <p>운영자 알림의 "실패 사유 요약"과 조치 힌트를 만드는 입력이다. 수신처는 <b>마스킹된 값</b>만
 * 담는다 — 이 값은 로그와 알림 문구에 그대로 실리기 때문이다.
 *
 * @param channel         SMS | EMAIL
 * @param recipientName   수신 사감 이름
 * @param maskedRecipient 마스킹된 수신처(예: {@code 010****5678})
 * @param reason          제공자가 돌려준 실패 사유
 */
public record ChannelFailure(String channel, String recipientName, String maskedRecipient, String reason) {

    public ChannelFailure {
        channel = channel == null ? "" : channel;
        recipientName = recipientName == null ? "" : recipientName;
        maskedRecipient = maskedRecipient == null ? "" : maskedRecipient;
        reason = reason == null || reason.isBlank() ? "알 수 없는 오류" : reason.trim();
    }
}
