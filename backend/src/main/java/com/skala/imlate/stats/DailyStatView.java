package com.skala.imlate.stats;

import java.time.LocalDate;

/**
 * 일자별 통계 한 줄(SPEC §7.2). 프론트 차트가 그대로 사용한다.
 *
 * @param date           통계 일자(KST)
 * @param uniqueVisitors 순 방문자 수
 * @param pageViews      페이지뷰(API 요청 수)
 * @param registrations  등록 건수
 */
public record DailyStatView(LocalDate date, long uniqueVisitors, long pageViews, long registrations) {
}
