package com.skala.imlate.registration.web.dto;

import java.time.LocalDate;

/**
 * 공개용 등록 현황 요약(SPEC §5.5). 개인정보(PII)를 포함하지 않는다.
 *
 * <p>등록 인원 수는 교육생이 알아야 할 정보가 아니므로 응답에서 제외한다(사감은 조회 페이지에서 본다).
 * 등록 화면에는 "지금 등록할 수 있는지"만 필요하다.
 *
 * @param date 복귀 대상일
 * @param open 지금 등록 가능한지
 */
public record RegistrationSummaryResponse(LocalDate date, boolean open) {
}
