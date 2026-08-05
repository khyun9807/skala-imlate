package com.skala.imlate.notification.service;

import java.time.LocalDate;

/**
 * 사감 발송 1회 실행 결과 요약.
 *
 * @param date         발송 대상일
 * @param skipped      발송을 건너뛰었는지 여부
 * @param skipReason   건너뛴 사유(NO_REGISTRATION | ALREADY_SENT | LOCK_NOT_ACQUIRED | DISABLED |
 *                     NO_SUPERVISOR | NO_FAILURE). 발송했으면 null
 * @param targetCount  발송 시점의 복귀 등록 인원 수
 * @param smsSuccess   문자 성공 건수
 * @param smsFailed    문자 실패 건수
 * @param emailSuccess 이메일 성공 건수
 * @param emailFailed  이메일 실패 건수
 * @param lookupUrl    안내에 포함한 조회 페이지 URL(건너뛴 경우 null 가능)
 */
public record DispatchSummary(LocalDate date, boolean skipped, String skipReason,
                              int targetCount, int smsSuccess, int smsFailed,
                              int emailSuccess, int emailFailed, String lookupUrl) {

    /** 사유가 있는 건너뜀 결과. */
    public static DispatchSummary ofSkipped(LocalDate date, String reason) {
        return new DispatchSummary(date, true, reason, 0, 0, 0, 0, 0, null);
    }

    /** 인원은 알지만 발송하지 않은 경우(예: 수신 사감 미설정). */
    public static DispatchSummary ofSkipped(LocalDate date, String reason, int targetCount) {
        return new DispatchSummary(date, true, reason, targetCount, 0, 0, 0, 0, null);
    }

    /** 실제 발송을 수행한 결과. */
    public static DispatchSummary ofSent(LocalDate date, int targetCount, int smsSuccess, int smsFailed,
                                         int emailSuccess, int emailFailed, String lookupUrl) {
        return new DispatchSummary(date, false, null, targetCount,
                smsSuccess, smsFailed, emailSuccess, emailFailed, lookupUrl);
    }

    /** 실패가 하나도 없었는지. */
    public boolean allSucceeded() {
        return !skipped && smsFailed == 0 && emailFailed == 0;
    }
}
