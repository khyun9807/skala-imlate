package com.skala.imlate.registration.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 등록 취소 처리 결과.
 *
 * <p>실패(등록 없음·비밀번호 불일치·시도 초과)는 이 타입으로 표현하지 않고 예외로 던진다.
 * 성공 경로만 값으로 돌려주므로 호출자가 실패 분기를 잊을 수 없다.
 *
 * @param date          복귀 대상일
 * @param cancelledAt   취소 시각(KST)
 * @param alreadyCancelled 이미 취소되어 있어 상태가 바뀌지 않았으면 true(멱등 재요청)
 */
public record CancelResult(LocalDate date, LocalDateTime cancelledAt, boolean alreadyCancelled) {
}
