package com.skala.imlate.registration.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 등록 창 상태 응답 DTO. 프론트가 <b>서버 시간</b> 기준으로 마감 카운트다운을 표시하는 데 쓴다.
 *
 * @param date              복귀 대상일(KST 오늘)
 * @param open              지금 등록 가능한지
 * @param serverTime        서버 현재 시각(KST 오프셋 포함)
 * @param opensAt           오늘의 등록 시작 시각
 * @param closesAt          오늘의 등록 마감 시각(이 시각부터 거부)
 * @param returnTime        연장 복귀 시각(23:30) — 안내 문구용
 * @param curfewTime        원래 통금 시각(22:30) — 안내 문구용
 * @param secondsUntilClose 마감까지 남은 초(이미 지났으면 0)
 */
public record RegistrationWindow(LocalDate date, boolean open,
                                 OffsetDateTime serverTime, OffsetDateTime opensAt,
                                 OffsetDateTime closesAt,
                                 // 안내 시각은 "23:30" / "22:30" 형태로 내려간다(기본 직렬화는 초까지 붙는다).
                                 @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
                                 LocalTime returnTime,
                                 @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
                                 LocalTime curfewTime,
                                 long secondsUntilClose) {
}
