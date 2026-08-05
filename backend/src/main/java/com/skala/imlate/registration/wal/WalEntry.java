package com.skala.imlate.registration.wal;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Redis WAL 에 기록되는 등록 선행 로그 1건(R7).
 *
 * <p>Redis HASH 의 값으로 JSON 직렬화되어 저장된다. 필드를 추가/삭제하면 기존 저장분과 호환되지 않으므로
 * {@code imlateObjectMapper} 의 {@code FAIL_ON_UNKNOWN_PROPERTIES=false} 설정에 의존한다.
 *
 * @param walId            WAL 항목 식별자(UUID 문자열, DB {@code wal_id} 와 1:1)
 * @param registrationDate 복귀 대상일
 * @param className        반
 * @param studentName      이름
 * @param roomNumber       기숙사 호수
 * @param registeredAt     등록 요청 시각(KST)
 * @param status           진행 상태
 * @param clientIp         요청 클라이언트 IP(장애 추적용, 없으면 "unknown")
 */
public record WalEntry(String walId, LocalDate registrationDate, String className,
                       String studentName, String roomNumber,
                       LocalDateTime registeredAt, WalStatus status, String clientIp) {

    /** 동일인 판정 키의 구분자. {@code ReturnRegistration#personKey()} 와 동일해야 한다. */
    public static final String PERSON_KEY_DELIMITER = "|";

    public WalEntry {
        // 상태가 비어 있는 예전 JSON 이 섞여 들어와도 안전하게 다루기 위한 보정.
        if (status == null) {
            status = WalStatus.PENDING;
        }
        if (clientIp == null) {
            clientIp = "unknown";
        }
    }

    /**
     * 상태만 바꾼 복사본을 만든다.
     *
     * @param s 새 상태
     * @return 상태가 교체된 새 WAL 항목
     */
    public WalEntry withStatus(WalStatus s) {
        return new WalEntry(walId, registrationDate, className, studentName, roomNumber,
                registeredAt, s, clientIp);
    }

    /**
     * 동일인 판정 키({@code date|class|name|room}).
     *
     * <p>JSON 으로 저장되지 않도록 {@code @JsonIgnore} 를 명시한다.
     *
     * @return 동일인 판정 키
     */
    @JsonIgnore
    public String personKey() {
        return registrationDate + PERSON_KEY_DELIMITER + className
                + PERSON_KEY_DELIMITER + studentName
                + PERSON_KEY_DELIMITER + roomNumber;
    }
}
