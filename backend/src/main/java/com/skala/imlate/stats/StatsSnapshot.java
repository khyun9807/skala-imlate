package com.skala.imlate.stats;

/**
 * 방문/등록 통계 요약(SPEC §7.2). PII 를 담지 않으므로 공개 API 로 노출해도 안전하다.
 *
 * @param totalVisitors      누적 순 방문자 수(HyperLogLog 추정치)
 * @param todayVisitors      오늘 순 방문자 수
 * @param totalPageViews     누적 페이지뷰(API 요청 수)
 * @param todayPageViews     오늘 페이지뷰
 * @param todayRegistrations 오늘 등록 건수
 * @param totalRegistrations 누적 등록 건수
 */
public record StatsSnapshot(long totalVisitors, long todayVisitors,
                            long totalPageViews, long todayPageViews,
                            long todayRegistrations, long totalRegistrations) {

    /** 통계를 전혀 얻지 못했을 때 사용하는 0 스냅샷. */
    public static StatsSnapshot empty() {
        return new StatsSnapshot(0L, 0L, 0L, 0L, 0L, 0L);
    }
}
