package com.skala.imlate.registration.web.dto;

import java.time.LocalDate;

/**
 * 공개용 등록 현황 요약(SPEC §5.5). 개인정보(PII)를 포함하지 않는다.
 *
 * @param date  복귀 대상일
 * @param count 현재까지 등록 인원 수
 * @param open  지금 등록 가능한지
 */
public record RegistrationSummaryResponse(LocalDate date, long count, boolean open) {
}
