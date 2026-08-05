package com.skala.imlate.notification.ops;

import java.time.LocalDate;
import java.util.List;

/**
 * "발송 시도를 끝냈다"는 신호 1건.
 *
 * <p>성공·실패·건너뜀을 가리지 않고 <b>시도가 끝났다는 사실</b>을 담는다. 밖(CloudWatch 알람)에서
 * 판정하는 것은 "21:50 에 이 신호가 오지 않았다" 이므로, 결과가 무엇이든 신호 자체는 나가야 한다.
 *
 * @param kind        DISPATCH(정기 발송) | RETRY(실패 재시도)
 * @param date        대상일
 * @param result      결과 요약(SENT | SKIPPED:NO_REGISTRATION 등)
 * @param targetCount 명단 인원 수
 * @param failures    최종 실패 목록(없으면 빈 목록)
 */
public record DispatchHeartbeat(Kind kind, LocalDate date, String result,
                                int targetCount, List<ChannelFailure> failures) {

    /** 하트비트를 유발한 실행 종류. */
    public enum Kind {
        /** 21:50 정기 발송(알람이 감시하는 대상). */
        DISPATCH,
        /** 22:05 / 22:20 실패 재시도. */
        RETRY
    }

    public DispatchHeartbeat {
        failures = failures == null ? List.of() : List.copyOf(failures);
        result = result == null || result.isBlank() ? "UNKNOWN" : result;
    }

    /** 실패 건수(= 지표 {@code DispatchFailures} 값). */
    public int failureCount() {
        return failures.size();
    }
}
