package com.skala.imlate.stats.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.skala.imlate.common.security.AccessTokenService;
import com.skala.imlate.stats.DailyStatView;
import com.skala.imlate.stats.StatsQueryService;
import com.skala.imlate.stats.StatsSnapshot;

/**
 * 통계 조회 API(SPEC §7.3).
 *
 * <ul>
 *   <li>{@code GET /api/v1/stats/summary} — 공개. PII 가 없는 요약 수치만 반환한다.</li>
 *   <li>{@code GET /api/v1/stats/daily} — 사감 조회 토큰 필요.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final StatsQueryService statsQueryService;
    private final AccessTokenService accessTokenService;
    private final Clock clock;

    public StatsController(StatsQueryService statsQueryService,
                           AccessTokenService accessTokenService,
                           Clock clock) {
        this.statsQueryService = statsQueryService;
        this.accessTokenService = accessTokenService;
        this.clock = clock;
    }

    /** 총 방문자·오늘 방문자·페이지뷰·등록 수 요약(공개). */
    @GetMapping("/summary")
    public StatsSnapshot summary() {
        return statsQueryService.snapshot();
    }

    /**
     * 일자별 통계. 조회 토큰은 종료일({@code to}, 없으면 오늘) 기준으로 검증한다.
     *
     * @param from  시작일(생략 시 최근 30일)
     * @param to    종료일(생략 시 오늘)
     * @param token 사감 조회 토큰
     */
    @GetMapping("/daily")
    public List<DailyStatView> daily(
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "token", required = false) String token) {

        LocalDate tokenDate = (to != null) ? to : LocalDate.now(clock);
        // 토큰 검증을 기간 검증보다 먼저 수행한다(인증 → 입력 검증 순서).
        accessTokenService.requireValid(tokenDate, token);
        return statsQueryService.daily(from, to);
    }
}
