package com.skala.imlate.stats.domain;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일별 통계 스냅샷 저장소(SPEC §7.2).
 */
public interface DailyStatRepository extends JpaRepository<DailyStat, LocalDate> {

    /** 기간 내 스냅샷을 일자 오름차순으로 조회한다. */
    List<DailyStat> findByStatDateBetweenOrderByStatDateAsc(LocalDate from, LocalDate to);

    /** {@code before} 이전(미포함) 날짜의 페이지뷰 합계. Redis 총계 키 유실 시 보정에 쓴다. */
    @Query("select coalesce(sum(d.pageViews), 0L) from DailyStat d where d.statDate < :before")
    Long sumPageViewsBefore(@Param("before") LocalDate before);

    /** {@code before} 이전(미포함) 날짜의 순 방문자 합계(일자별 합이므로 실제 순 방문자보다 클 수 있다). */
    @Query("select coalesce(sum(d.uniqueVisitors), 0L) from DailyStat d where d.statDate < :before")
    Long sumUniqueVisitorsBefore(@Param("before") LocalDate before);

    /** {@code before} 이전(미포함) 날짜의 등록 건수 합계. */
    @Query("select coalesce(sum(d.registrations), 0L) from DailyStat d where d.statDate < :before")
    Long sumRegistrationsBefore(@Param("before") LocalDate before);

    /** 보존 기간이 지난 스냅샷을 삭제한다. */
    @Modifying
    @Transactional
    @Query("delete from DailyStat d where d.statDate < :threshold")
    int deleteOlderThan(@Param("threshold") LocalDate threshold);
}
