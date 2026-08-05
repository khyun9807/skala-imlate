package com.skala.imlate.notification.template;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.skala.imlate.registration.service.ReconciliationReport;
import com.skala.imlate.stats.StatsSnapshot;

/**
 * 사감에게 보낼 안내문을 만들기 위한 입력 묶음.
 *
 * <p>{@code verification} / {@code stats} 는 호출부(발송 서비스)가 그대로 채워 넣지만
 * {@link CurfewNoticeRenderer} 는 이를 <b>문구에 렌더링하지 않는다</b>. 사감에게 보이는 문자·메일에는
 * 대사 결과나 이용 통계를 넣지 않기로 했기 때문이다(기록은 로그·관리 API 로 유지). 두 값은 null 이어도 안전하다.
 *
 * @param date         복귀 대상일
 * @param rows         복귀 명단(번호·반·이름·호수)
 * @param lookupUrl    전체 명단 조회 페이지 URL(토큰 포함)
 * @param returnTime   연장 복귀 시각(기본 23:30)
 * @param curfewTime   원래 통금 시각(기본 22:30)
 * @param verification Redis WAL ↔ DB 대사 결과(문구에는 쓰이지 않음, null 가능)
 * @param stats        방문/등록 통계(문구에는 쓰이지 않음, null 가능)
 */
public record NoticePayload(LocalDate date, List<Row> rows, String lookupUrl,
                            LocalTime returnTime, LocalTime curfewTime,
                            ReconciliationReport verification, StatsSnapshot stats) {

    public NoticePayload {
        // 목록은 항상 불변 복사본으로 고정한다(렌더러가 안심하고 순회하도록).
        rows = rows == null ? List.of() : List.copyOf(rows);
        lookupUrl = lookupUrl == null ? "" : lookupUrl;
        if (returnTime == null) {
            returnTime = LocalTime.of(23, 30);
        }
        if (curfewTime == null) {
            curfewTime = LocalTime.of(22, 30);
        }
    }

    /** 복귀 예정 총 인원. */
    public int totalCount() {
        return rows.size();
    }

    /**
     * 명단 한 줄.
     *
     * @param no          표시 번호(1부터)
     * @param className   반
     * @param studentName 이름
     * @param roomNumber  기숙사 호수
     */
    public record Row(int no, String className, String studentName, String roomNumber) {

        public Row {
            className = className == null ? "" : className;
            studentName = studentName == null ? "" : studentName;
            roomNumber = roomNumber == null ? "" : roomNumber;
        }
    }
}
