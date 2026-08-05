package com.skala.imlate.notification.channel;

/**
 * 이메일 발송 채널 추상화. 구현체는 {@code imlate.email.provider} 값으로 선택된다.
 */
public interface EmailSender {

    /**
     * 이메일을 발송한다.
     *
     * <p>구현체는 <b>절대 예외를 전파하지 않는다.</b> 실패는 {@link SendResult#fail(String)} 로 돌려준다.
     *
     * @param toEmail  수신 이메일 주소
     * @param subject  제목
     * @param textBody 텍스트 본문(고정폭 표)
     * @param htmlBody HTML 본문(인라인 CSS)
     * @return 발송 결과
     */
    SendResult send(String toEmail, String subject, String textBody, String htmlBody);

    /** 로그·이력에 남길 제공자 이름. */
    String providerName();
}
