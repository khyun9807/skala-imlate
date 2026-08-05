package com.skala.imlate.registration.event;

import java.time.LocalDate;

/**
 * 신규 등록이 DB 에 저장되었을 때 발행되는 도메인 이벤트(SPEC §7.1).
 *
 * <p>중복 등록(멱등 응답)에서는 발행하지 않는다. stats 모듈이 이 이벤트를 구독해
 * {@code imlate:stats:reg:{date}} 카운터를 증가시킨다.
 *
 * <p>발행 시점은 등록 INSERT 트랜잭션 <b>내부</b>이므로 구독 측이
 * {@code @EventListener} 와 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 중
 * 무엇을 쓰더라도 정상 수신된다.
 *
 * @param date      복귀 대상일
 * @param className 반
 */
public record RegistrationCreatedEvent(LocalDate date, String className) {
}
