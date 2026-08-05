package com.skala.imlate.registration.web.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.skala.imlate.registration.domain.ReturnRegistration;

/**
 * 등록 결과 응답(SPEC §5.5).
 *
 * <p>신규 생성이면 HTTP 201, 이미 등록된 경우면 HTTP 200 + {@code duplicate=true} 로 내려간다.
 *
 * @param id               등록 PK
 * @param registrationDate 복귀 대상일
 * @param className        반
 * @param studentName      이름
 * @param roomNumber       기숙사 호수
 * @param registeredAt     등록 시각
 * @param duplicate        이미 등록되어 있던 경우 true
 * @param returnTime       연장 복귀 시각(기본 23:30)
 */
public record RegistrationResponse(Long id, LocalDate registrationDate, String className,
                                   String studentName, String roomNumber,
                                   LocalDateTime registeredAt, boolean duplicate,
                                   // SPEC §5.5 예시대로 "23:30" 형태로 내려간다(기본 직렬화는 "23:30:00").
                                   @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
                                   LocalTime returnTime) {

    /**
     * 엔티티로부터 응답 DTO 를 만든다.
     *
     * @param registration 등록 레코드
     * @param duplicate    중복 여부
     * @param returnTime   연장 복귀 시각
     * @return 응답 DTO
     */
    public static RegistrationResponse of(ReturnRegistration registration, boolean duplicate, LocalTime returnTime) {
        return new RegistrationResponse(
                registration.getId(),
                registration.getRegistrationDate(),
                registration.getClassName(),
                registration.getStudentName(),
                registration.getRoomNumber(),
                registration.getRegisteredAt(),
                duplicate,
                returnTime);
    }
}
