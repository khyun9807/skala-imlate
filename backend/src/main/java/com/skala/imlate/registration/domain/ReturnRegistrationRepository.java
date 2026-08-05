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

    /** 해당 일자의 전체 명단을 반 → 이름 순으로 조회한다(조회 페이지·발송 명단의 표시 순서). */
    List<ReturnRegistration> findByRegistrationDateOrderByClassNameAscStudentNameAsc(LocalDate date);

    /** 동일인(날짜+반+이름+호수) 등록을 조회한다. 중복 등록 멱등 처리에 사용한다. */
    Optional<ReturnRegistration> findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
            LocalDate date, String className, String studentName, String roomNumber);

    /** 해당 일자의 등록 인원 수. */
    long countByRegistrationDate(LocalDate date);

    /** WAL 식별자로 이미 저장된 레코드가 있는지 확인한다(대사 복구 시 중복 INSERT 방지). */
    boolean existsByWalId(String walId);
}
