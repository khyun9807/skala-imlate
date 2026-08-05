package com.skala.imlate.registration.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 야간 복귀 등록 저장소.
 *
 * <p>조회 메서드 이름은 SPEC §5.1 그대로 유지한다(다른 모듈이 그대로 호출한다).
 */
public interface ReturnRegistrationRepository extends JpaRepository<ReturnRegistration, Long> {

    /**
     * 해당 일자의 <b>취소분 포함</b> 전체 행을 반 → 이름 순으로 조회한다.
     *
     * <p><b>대사(ReconciliationService) 전용이다.</b> 취소된 행도 "DB 에 존재한다"고 봐야
     * WAL 을 근거로 되살리는 일이 없다. 사감 명단이나 조회 페이지에는 절대 쓰지 말 것 —
     * 그쪽은 {@link #findByRegistrationDateAndCancelledAtIsNullOrderByClassNameAscStudentNameAsc} 를 쓴다.
     */
    List<ReturnRegistration> findByRegistrationDateOrderByClassNameAscStudentNameAsc(LocalDate date);

    /**
     * 해당 일자의 <b>유효한(취소되지 않은)</b> 명단을 반 → 이름 순으로 조회한다.
     *
     * <p>사감 발송 명단·조회 페이지가 쓰는 기본 조회다. {@code idx_return_registration_date_active}
     * 복합 인덱스를 탄다.
     */
    List<ReturnRegistration> findByRegistrationDateAndCancelledAtIsNullOrderByClassNameAscStudentNameAsc(
            LocalDate date);

    /**
     * 동일인(날짜+반+이름+호수) 등록을 조회한다. 중복 등록 멱등 처리와 취소 검증에 사용한다.
     *
     * <p>취소 여부와 무관하게 찾는다 — 유니크 제약이 취소된 행에도 그대로 걸려 있으므로,
     * 재등록 시 이 조회로 취소된 행을 찾아 되살려야 한다.
     */
    Optional<ReturnRegistration> findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
            LocalDate date, String className, String studentName, String roomNumber);

    /** 해당 일자의 <b>유효한</b> 등록 인원 수(취소분 제외). */
    long countByRegistrationDateAndCancelledAtIsNull(LocalDate date);

    /** WAL 식별자로 이미 저장된 레코드가 있는지 확인한다(대사 복구 시 중복 INSERT 방지). */
    boolean existsByWalId(String walId);
}
