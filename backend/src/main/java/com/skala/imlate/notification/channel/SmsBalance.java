package com.skala.imlate.notification.channel;

/**
 * 문자 제공자의 잔여 발송 건수.
 *
 * <p>잔액이 0 이 되면 21:50 발송이 통째로 실패하고, 그 결과는 "교육생이 기숙사에 못 들어감"이다.
 * 그래서 잔액은 발송 직후 한 번 확인해 임계값 미만이면 경고한다.
 *
 * @param sms SMS(단문) 잔여 건수
 * @param lms LMS(장문) 잔여 건수
 * @param mms MMS 잔여 건수
 */
public record SmsBalance(int sms, int lms, int mms) {

    /**
     * 경고 판정에 쓰는 값. 사감 명단은 본문이 길어 대부분 LMS 로 나가지만,
     * 인원이 적은 날은 SMS 로도 나갈 수 있으므로 <b>둘 중 작은 쪽</b>을 기준으로 본다.
     */
    public int effectiveRemaining() {
        return Math.min(sms, lms);
    }

    /** 로그·알림 문구용 요약(예: {@code SMS 6329건 / LMS 2109건}). */
    public String describe() {
        return "SMS " + sms + "건 / LMS " + lms + "건";
    }
}
