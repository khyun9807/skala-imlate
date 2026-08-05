package com.skala.imlate.registration.wal;

/**
 * WAL 항목의 진행 상태.
 *
 * <p>대사(reconciliation)는 이 상태가 아니라 <b>실제 DB 존재 여부</b>로만 누락을 판정한다(SPEC §5.4).
 * 상태값은 장애 원인 추적용 메타데이터에 가깝다.
 */
public enum WalStatus {

    /** DB 기록 전(선행 로깅 직후). */
    PENDING,
    /** DB 기록까지 완료. */
    COMMITTED,
    /** DB 기록 실패. */
    FAILED
}
