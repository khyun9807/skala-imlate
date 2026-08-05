package com.skala.imlate.notification.domain;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 사감 발송 이력 저장소.
 */
public interface NotificationDispatchRepository extends JpaRepository<NotificationDispatch, Long> {

    /** 해당 일자의 모든 발송 이력. */
    List<NotificationDispatch> findByDispatchDate(LocalDate date);

    /** 해당 일자의 발송 이력을 저장 순서대로 조회(관리 API 표시용). */
    List<NotificationDispatch> findByDispatchDateOrderByIdAsc(LocalDate date);

    /** 특정 수신처·채널에 대해 주어진 상태의 이력이 있는지. */
    boolean existsByDispatchDateAndChannelAndRecipientAndStatus(LocalDate d, String c, String r, String s);

    /** 해당 일자·상태의 이력 건수(중복 발송 방지 판정에 사용). */
    long countByDispatchDateAndStatus(LocalDate date, String status);
}
