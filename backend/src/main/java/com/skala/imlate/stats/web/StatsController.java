package com.skala.imlate.stats.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.skala.imlate.common.security.AdminKeyGuard;
import com.skala.imlate.stats.DailyStatView;
import com.skala.imlate.stats.StatsQueryService;
import com.skala.imlate.stats.StatsSnapshot;

/**
 * 통계 조회 API(SPEC §7.3).
 *
 * <p>통계는 교육생·사감 화면에 노출하지 않는다. 집계는 예전과 동일하게 계속 수행하되
 * <b>조회는 운영자 전용</b>으로 제한한다. 두 엔드포인트 모두 {@code X-Admin-Key} 로 보호된다.
 *
 * <ul>
 *   <li>{@code GET /api/v1/stats/summary} — 요약 수치</li>
 *   <li>{@code GET /api/v1/stats/daily} — 일자별 통계</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final StatsQueryService statsQueryService;
    private final AdminKeyGuard adminKeyGuard;

    /**
     * @param statsQueryService 통계 조회 서비스
     * @param adminKeyGuard     관리자 키 검증
     */
    public StatsController(StatsQueryService statsQueryService, AdminKeyGuard adminKeyGuard) {
        this.statsQueryService = statsQueryService;
        this.adminKeyGuard = adminKeyGuard;
    }

    /**
     * 총 방문자·오늘 방문자·페이지뷰·등록 수 요약(운영자 전용).
     *
     * @param adminKey 관리자 키 헤더
     */
    @GetMapping("/summary")
    public StatsSnapshot summary(
            @RequestHeader(value = AdminKeyGuard.HEADER_NAME, required = false) String adminKey) {
        adminKeyGuard.require(adminKey);
        return statsQueryService.snapshot();
    }

    /**
     * 일자별 통계(운영자 전용).
     *
     * @param adminKey 관리자 키 헤더
     * @param from     시작일(생략 시 최근 30일)
     * @param to       종료일(생략 시 오늘)
     */
    @GetMapping("/daily")
    public List<DailyStatView> daily(
            @RequestHeader(value = AdminKeyGuard.HEADER_NAME, required = false) String adminKey,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        // 인증을 기간 검증보다 먼저 수행한다(인증 → 입력 검증 순서).
        adminKeyGuard.require(adminKey);
        return statsQueryService.daily(from, to);
    }
}
