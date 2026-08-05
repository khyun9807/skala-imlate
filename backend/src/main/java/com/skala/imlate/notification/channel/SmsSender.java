package com.skala.imlate.notification.channel;

/**
 * 문자 발송 채널 추상화. 구현체는 {@code imlate.sms.provider} 값으로 선택된다.
 */
public interface SmsSender {

    /**
     * 문자를 발송한다.
     *
     * <p>구현체는 <b>절대 예외를 전파하지 않는다.</b> 실패는 {@link SendResult#fail(String)} 로 돌려준다.
     *
     * @param toPhone 수신 번호(하이픈 포함 가능)
     * @param title   LMS 제목(본문이 90바이트를 넘어 LMS 로 전환될 때 사용)
     * @param message 본문
     * @return 발송 결과
     */
    SendResult send(String toPhone, String title, String message);

    /** 로그·이력에 남길 제공자 이름. */
    String providerName();
}
