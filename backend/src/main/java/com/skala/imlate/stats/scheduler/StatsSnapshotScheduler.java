package com.skala.imlate.stats.scheduler;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.StatsProperties;
import com.skala.imlate.stats.DailyStatView;
import com.skala.imlate.stats.StatsQueryService;
import com.skala.imlate.stats.domain.DailyStat;
import com.skala.imlate.stats.domain.DailyStatRepository;

/**
 * Redis 통계 카운터를 {@code daily_stat} 테이블로 영속화하는 스케줄러(SPEC §7.2).
 *
 * <ul>
 *   <li>{@code imlate.stats.snapshot-cron}(기본 {@code 0 5 0 * * *}) — 전일분 확정 + 보존 기간 초과분 정리</li>
 *   <li>{@code imlate.stats.today-snapshot-cron}(기본 {@code 0 55 23 * * *}) — 당일분 선반영
 *       (22:10 사감 발송 이후의 최종 상태를 남기기 위함)</li>
 * </ul>
 *
 * <p>Redis 를 읽을 수 없거나 값이 모두 0 이면 <b>기록하지 않는다</b>.
 * 이미 저장된 스냅샷을 0 으로 덮어써 과거 통계를 잃는 사고를 막기 위함이다.
 */
@Component
public class StatsSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatsSnapshotScheduler.class);

    private final StatsQueryService statsQueryService;
    private final DailyStatRepository dailyStatRepository;
    private final StatsProperties statsProperties;
    private final Clock clock;

    public StatsSnapshotScheduler(StatsQueryService statsQueryService,
                                  DailyStatRepository dailyStatRepository,
                                  StatsProperties statsProperties,
                                  Clock clock) {
        this.statsQueryService = statsQueryService;
        this.dailyStatRepository = dailyStatRepository;
        this.statsProperties = statsProperties;
        this.clock = clock;
    }

    /** 자정 직후 전일 통계를 확정하고 보존 기간이 지난 스냅샷을 정리한다. */
    @Scheduled(cron = "${imlate.stats.snapshot-cron:0 5 0 * * *}", zone = "${imlate.timezone:Asia/Seoul}")
    public void snapshotPreviousDay() {
        if (!statsProperties.enabled()) {
            return;
        }
        LocalDate target = LocalDate.now(clock).minusDays(1);
        upsert(target);
        purgeExpired();
    }

    /** 하루가 끝나기 직전(23:55) 당일 통계를 미리 반영한다. */
    @Scheduled(cron = "${imlate.stats.today-snapshot-cron:0 55 23 * * *}", zone = "${imlate.timezone:Asia/Seoul}")
    public void snapshotToday() {
        if (!statsProperties.enabled()) {
            return;
        }
        upsert(LocalDate.now(clock));
    }

    /**
     * 지정 일자의 통계를 {@code daily_stat} 에 upsert 한다(운영 수동 실행에도 사용 가능).
     *
     * @param date 통계 일자(KST)
     * @return 저장했으면 true, 값이 없어 건너뛰었거나 실패했으면 false
     */
    public boolean upsert(LocalDate date) {
        if (date == null) {
            return false;
        }
        try {
            List<DailyStatView> views = statsQueryService.daily(date, date);
            if (views.isEmpty()) {
                return false;
            }
            DailyStatView view = views.get(0);
            if (view.pageViews() <= 0L && view.uniqueVisitors() <= 0L && view.registrations() <= 0L) {
                // Redis 장애로 0 이 나온 경우까지 포함해, 빈 값으로는 기존 스냅샷을 덮지 않는다.
                log.info("일별 통계 스냅샷 생략(집계 값 없음): date={}", date);
                return false;
            }

            // 식별자(stat_date)가 지정된 엔티티이므로 save() 는 merge 로 동작해 존재 여부에 관계없이 upsert 된다.
            DailyStat stat = DailyStat.of(date, view.pageViews(), view.uniqueVisitors(),
                    view.registrations(), LocalDateTime.now(clock));
            dailyStatRepository.save(stat);
            log.info("일별 통계 스냅샷 저장: date={}, pv={}, uv={}, reg={}",
                    date, view.pageViews(), view.uniqueVisitors(), view.registrations());
            return true;
        } catch (Exception ex) {
            // 스케줄러가 죽지 않도록 모든 예외를 삼킨다.
            log.warn("일별 통계 스냅샷 실패: date={}, cause={}", date, ex.toString());
            return false;
        }
    }

    /** 보존 기간({@code imlate.stats.retention-days})이 지난 스냅샷을 삭제한다. */
    private void purgeExpired() {
        try {
            LocalDate threshold = LocalDate.now(clock).minusDays(statsProperties.retentionDays());
            int deleted = dailyStatRepository.deleteOlderThan(threshold);
            if (deleted > 0) {
                log.info("보존 기간 초과 일별 통계 {}건 삭제(기준일 이전: {})", deleted, threshold);
            }
        } catch (Exception ex) {
            log.warn("일별 통계 정리 실패: {}", ex.toString());
        }
    }
}
