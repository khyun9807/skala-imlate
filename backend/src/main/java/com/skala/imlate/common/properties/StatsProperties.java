package com.skala.imlate.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 방문자/등록 통계 설정(`imlate.stats.*`).
 *
 * @param enabled       통계 수집 사용 여부
 * @param retentionDays 일별 통계 Redis 키 보존 일수
 * @param snapshotCron  일별 통계를 DB로 영속화하는 cron(기본 "0 5 0 * * *")
 */
@ConfigurationProperties(prefix = "imlate.stats")
public record StatsProperties(boolean enabled, int retentionDays, String snapshotCron) {

    public StatsProperties {
        if (retentionDays <= 0) {
            retentionDays = 400;
        }
        if (snapshotCron == null || snapshotCron.isBlank()) {
            snapshotCron = "0 5 0 * * *";
        }
    }
}
