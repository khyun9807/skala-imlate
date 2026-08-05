package com.skala.imlate.stats.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 일별 통계 스냅샷(SPEC §7.2, 테이블 {@code daily_stat}).
 *
 * <p>Redis 카운터를 매일 영속화한 값이다. Redis 가 비워지거나 TTL 이 지나도
 * 과거 통계를 잃지 않도록 하는 것이 목적이며, 조회 시 DB fallback 으로 쓰인다.
 */
@Entity
@Table(name = "daily_stat")
public class DailyStat {

    /** 통계 일자(PK, KST 기준). */
    @Id
    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "page_views", nullable = false)
    private long pageViews;

    @Column(name = "unique_visitors", nullable = false)
    private long uniqueVisitors;

    @Column(name = "registrations", nullable = false)
    private long registrations;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA 전용 기본 생성자. */
    protected DailyStat() {
        // JPA
    }

    private DailyStat(LocalDate statDate, long pageViews, long uniqueVisitors,
                      long registrations, LocalDateTime updatedAt) {
        this.statDate = statDate;
        this.pageViews = pageViews;
        this.uniqueVisitors = uniqueVisitors;
        this.registrations = registrations;
        this.updatedAt = updatedAt;
    }

    /**
     * 스냅샷 생성 팩토리. 음수는 0으로 보정한다.
     *
     * @param statDate  통계 일자
     * @param updatedAt 갱신 시각(주입받은 {@code Clock} 으로 만든 값)
     */
    public static DailyStat of(LocalDate statDate, long pageViews, long uniqueVisitors,
                               long registrations, LocalDateTime updatedAt) {
        return new DailyStat(statDate,
                Math.max(0L, pageViews),
                Math.max(0L, uniqueVisitors),
                Math.max(0L, registrations),
                updatedAt);
    }

    /** 기존 스냅샷 값을 갱신한다. */
    public void update(long pageViews, long uniqueVisitors, long registrations, LocalDateTime updatedAt) {
        this.pageViews = Math.max(0L, pageViews);
        this.uniqueVisitors = Math.max(0L, uniqueVisitors);
        this.registrations = Math.max(0L, registrations);
        this.updatedAt = updatedAt;
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public long getPageViews() {
        return pageViews;
    }

    public long getUniqueVisitors() {
        return uniqueVisitors;
    }

    public long getRegistrations() {
        return registrations;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
