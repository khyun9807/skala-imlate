package com.skala.imlate.registration.service;

import com.skala.imlate.registration.domain.ReturnRegistration;

/**
 * 등록 처리 결과.
 *
 * @param registration 저장되었거나 이미 존재하던 등록 레코드
 * @param duplicate    이미 등록되어 있어 새로 만들지 않았으면 true(HTTP 200), 신규 생성이면 false(HTTP 201)
 */
public record RegistrationResult(ReturnRegistration registration, boolean duplicate) {
}
