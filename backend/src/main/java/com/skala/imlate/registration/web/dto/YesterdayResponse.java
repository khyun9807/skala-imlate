package com.skala.imlate.registration.web.dto;

import java.time.LocalDate;

/**
 * 어제 연장 복귀 인원 수(공개). 개인정보(PII)를 포함하지 않는다.
 *
 * <p>등록 화면 하단에 "어제 N명이 …" 안내 문구를 띄우기 위한 값이다.
 *
 * <p><b>왜 어제 인원은 공개하면서 오늘 인원은 감추는가</b> —
 * {@link RegistrationSummaryResponse} 는 오늘 등록 인원 수를 일부러 빼 놓았다.
 * 오늘 숫자는 <u>진행 중인 정보</u>라 "지금 몇 명이 밖에 있는지"를 알려 주는 셈이고,
 * 마감 직전에는 명단 규모가 그대로 노출된다. 반면 어제 숫자는 이미 끝난 하루의 합계라
 * 그것으로 오늘 누가 무엇을 하는지 알 수 없다. 이름·반·호수는 어느 쪽에도 담기지 않는다.
 *
 * <p>{@code count} 는 <b>취소분을 제외한 최종 인원</b>이다. 등록했다가 취소한 사람은
 * 실제로 23:30 개방을 이용하지 않았으므로 세지 않는다.
 *
 * @param date  대상일(어제)
 * @param count 그날 연장 복귀한 최종 인원 수. 0 이면 화면에 아무것도 띄우지 않는다
 */
public record YesterdayResponse(LocalDate date, long count) {
}
