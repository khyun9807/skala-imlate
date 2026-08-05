package com.skala.imlate.registration.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * WAL ↔ DB 대사 결과(R8, SPEC §5.4).
 *
 * <p>{@code walOnly} / {@code dbOnly} 는 <b>복구 후에도 남아 있는</b> 불일치만 담는다.
 * 표시 형식은 {@code "1반/홍길동/302"}.
 *
 * @param date           대상일
 * @param status         {@code CONSISTENT | RECOVERED | MISMATCH | WAL_UNAVAILABLE}
 * @param dbCount        DB 등록 건수
 * @param walCount       WAL 에 있는 서로 다른 인원 수(WAL 사용 불가 시 0)
 * @param recoveredCount 이번 대사에서 DB 로 복구한 건수
 * @param walOnly        WAL 에만 있고 DB 에 없는 인원
 * @param dbOnly         DB 에만 있고 WAL 에 없는 인원(WAL TTL 만료·Redis 장애 등)
 * @param checkedAt      대사 수행 시각
 */
public record ReconciliationReport(LocalDate date, String status,
                                   long dbCount, long walCount, long recoveredCount,
                                   List<String> walOnly,
                                   List<String> dbOnly,
                                   OffsetDateTime checkedAt) {

    /** WAL ↔ DB 가 완전히 일치. */
    public static final String STATUS_CONSISTENT = "CONSISTENT";
    /** 누락분을 DB 로 복구해 일치시켰음. */
    public static final String STATUS_RECOVERED = "RECOVERED";
    /** 복구 후에도 불일치가 남음(경고 수준). */
    public static final String STATUS_MISMATCH = "MISMATCH";
    /** Redis(WAL) 를 사용할 수 없어 대사를 수행하지 못함. */
    public static final String STATUS_WAL_UNAVAILABLE = "WAL_UNAVAILABLE";

    public ReconciliationReport {
        walOnly = walOnly == null ? List.of() : List.copyOf(walOnly);
        dbOnly = dbOnly == null ? List.of() : List.copyOf(dbOnly);
    }

    /**
     * 사람이 읽는 한 줄 요약. 문자/이메일 안내 문구에 사용한다(JSON 필드로는 노출하지 않는다).
     *
     * @return 예: {@code "DB 12 / WAL 12 (일치)"}
     */
    @JsonIgnore
    public String summaryText() {
        String current = status == null ? STATUS_MISMATCH : status;
        String label = switch (current) {
            case STATUS_CONSISTENT -> "일치";
            case STATUS_RECOVERED -> "복구 " + recoveredCount + "건 후 일치";
            case STATUS_WAL_UNAVAILABLE -> "WAL 확인 불가";
            default -> "불일치 " + (walOnly.size() + dbOnly.size()) + "건";
        };
        return "DB " + dbCount + " / WAL " + walCount + " (" + label + ")";
    }
}
