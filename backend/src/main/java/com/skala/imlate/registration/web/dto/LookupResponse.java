package com.skala.imlate.registration.web.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.skala.imlate.registration.service.ReconciliationReport;
import com.skala.imlate.stats.StatsSnapshot;

/**
 * 사감용 조회 페이지 데이터(R8, R9 / SPEC §5.5).
 *
 * <p>명단·반별/호수별 집계·WAL 대사 결과·방문 통계를 한 번에 내려준다.
 *
 * @param date        대상일
 * @param generatedAt 응답 생성 시각(서버 기준)
 * @param totalCount  총 등록 인원 수
 * @param returnTime  연장 복귀 시각(23:30)
 * @param curfewTime  원래 통금 시각(22:30)
 * @param items       명단(반 → 이름 순, {@code no} 는 1부터)
 * @param byClass     반별 인원 집계
 * @param byRoom      호수별 인원 집계
 * @param verification WAL ↔ DB 대사 결과
 * @param stats       방문/등록 통계
 */
public record LookupResponse(LocalDate date, OffsetDateTime generatedAt, long totalCount,
                             // SPEC §5.5 예시대로 "23:30" / "22:30" 형태로 내려간다.
                             @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
                             LocalTime returnTime,
                             @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
                             LocalTime curfewTime,
                             List<Item> items, List<ClassCount> byClass, List<RoomCount> byRoom,
                             ReconciliationReport verification, StatsSnapshot stats) {

    public LookupResponse {
        items = items == null ? List.of() : List.copyOf(items);
        byClass = byClass == null ? List.of() : List.copyOf(byClass);
        byRoom = byRoom == null ? List.of() : List.copyOf(byRoom);
    }

    /**
     * 명단 1행.
     *
     * @param no           1부터 시작하는 순번(반 → 이름 정렬 기준)
     * @param className    반
     * @param studentName  이름
     * @param roomNumber   기숙사 호수
     * @param registeredAt 등록 시각
     */
    public record Item(int no, String className, String studentName, String roomNumber,
                       LocalDateTime registeredAt) {
    }

    /**
     * 반별 인원 집계.
     *
     * @param className 반
     * @param count     인원 수
     */
    public record ClassCount(String className, long count) {
    }

    /**
     * 호수별 인원 집계.
     *
     * @param roomNumber 기숙사 호수
     * @param count      인원 수
     */
    public record RoomCount(String roomNumber, long count) {
    }
}
