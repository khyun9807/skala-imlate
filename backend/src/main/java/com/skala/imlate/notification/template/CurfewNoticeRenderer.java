package com.skala.imlate.notification.template;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 사감 안내 문구 렌더러(R4, R9).
 *
 * <p>문자는 짧게(반별 그룹핑), 이메일 텍스트는 고정폭 표, 이메일 HTML 은 인라인 CSS + 모바일 가독성을
 * 목표로 한다. 한글은 전각이라 폭이 2이므로, 표 정렬은 문자 수가 아니라 <b>표시 폭</b>으로 계산한다.
 *
 * <p>문구에는 사감에게 필요한 것만 담는다: 날짜·총원·반/이름/호수 목록·23:30 일괄 개방 안내·
 * 22:30 문 잠김 안내·조회 페이지 URL. WAL↔DB 대사 결과와 이용 통계는
 * {@link NoticePayload} 로 계속 전달되지만 <b>문구에는 넣지 않는다</b>(운영 기록은 로그·관리 API 로 남는다).
 */
@Component
public class CurfewNoticeRenderer {

    /** "8월 5일(수)" */
    private static final DateTimeFormatter DATE_KO =
            DateTimeFormatter.ofPattern("M월 d일(E)", Locale.KOREAN);
    /** "2026년 8월 5일(수)" */
    private static final DateTimeFormatter DATE_KO_FULL =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일(E)", Locale.KOREAN);
    /** "8/5" */
    private static final DateTimeFormatter DATE_SHORT =
            DateTimeFormatter.ofPattern("M/d", Locale.KOREAN);
    /** "23:30" */
    private static final DateTimeFormatter TIME_HHMM =
            DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN);
    /** 국내 문자 길이 판정 문자셋(문자 본문이 너무 길어지면 요약 모드로 전환). */
    private static final Charset EUC_KR = Charset.forName("EUC-KR");
    /** 이 바이트를 넘으면 문자 본문을 "반별 요약" 모드로 바꾼다(LMS 2000바이트 여유분 확보). */
    private static final int SMS_SAFE_BYTES = 1850;

    // ------------------------------------------------------------------
    // 문자(SMS/LMS)
    // ------------------------------------------------------------------

    /** 문자 제목(LMS 전환 시 사용). 예) {@code [기숙사] 8/5 23:30 복귀 12명} */
    public String smsTitle(NoticePayload payload) {
        return "[기숙사] " + DATE_SHORT.format(payload.date())
                + " " + TIME_HHMM.format(payload.returnTime())
                + " 복귀 " + payload.totalCount() + "명";
    }

    /** 문자 본문. 명단이 길면 반별 요약으로 자동 전환한다. */
    public String smsBody(NoticePayload payload) {
        String detailed = buildSmsBody(payload, true);
        if (detailed.getBytes(EUC_KR).length <= SMS_SAFE_BYTES) {
            return detailed;
        }
        // 명단이 너무 길어 문자 한 통에 담기 어렵다 → 반별 인원 요약 + 조회 링크로 안내한다.
        return buildSmsBody(payload, false);
    }

    private String buildSmsBody(NoticePayload payload, boolean withNames) {
        LocalTime returnTime = payload.returnTime();
        LocalTime curfewTime = payload.curfewTime();
        StringBuilder sb = new StringBuilder(512);

        sb.append("[기숙사 야간복귀 명단]\n");
        sb.append(DATE_KO.format(payload.date()))
                .append(' ').append(TIME_HHMM.format(returnTime))
                .append(" 복귀 ").append(payload.totalCount()).append("명\n\n");

        Map<String, List<NoticePayload.Row>> grouped = groupByClass(payload.rows());
        if (grouped.isEmpty()) {
            sb.append("· 등록된 인원이 없습니다.\n");
        } else if (withNames) {
            for (Map.Entry<String, List<NoticePayload.Row>> entry : grouped.entrySet()) {
                sb.append("· ").append(entry.getKey())
                        .append(" (").append(entry.getValue().size()).append("명)\n  ");
                List<String> names = new ArrayList<>(entry.getValue().size());
                for (NoticePayload.Row row : entry.getValue()) {
                    names.add(row.studentName() + " " + row.roomNumber());
                }
                sb.append(String.join(" / ", names)).append('\n');
            }
        } else {
            List<String> summary = new ArrayList<>(grouped.size());
            for (Map.Entry<String, List<NoticePayload.Row>> entry : grouped.entrySet()) {
                summary.add(entry.getKey() + " " + entry.getValue().size() + "명");
            }
            sb.append("· ").append(String.join(" / ", summary)).append('\n');
            sb.append("  (명단이 길어 이름/호수는 아래 링크에서 확인해 주세요)\n");
        }

        sb.append('\n');
        sb.append("※ ").append(TIME_HHMM.format(curfewTime)).append(" 이후 문은 잠기며 ")
                .append(TIME_HHMM.format(returnTime)).append("에 일괄 개방됩니다.\n");
        sb.append("전체 명단: ").append(payload.lookupUrl());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 이메일
    // ------------------------------------------------------------------

    /** 이메일 제목. */
    public String emailSubject(NoticePayload payload) {
        return "[기숙사 야간복귀] " + DATE_KO.format(payload.date())
                + " " + TIME_HHMM.format(payload.returnTime())
                + " 복귀 " + payload.totalCount() + "명 명단";
    }

    /** 이메일 텍스트 본문(고정폭 표). 전각 문자 폭을 반영해 열을 맞춘다. */
    public String emailText(NoticePayload payload) {
        LocalTime returnTime = payload.returnTime();
        LocalTime curfewTime = payload.curfewTime();
        StringBuilder sb = new StringBuilder(2048);

        String line = "=".repeat(60);
        sb.append(line).append('\n');
        sb.append(" 기숙사 야간복귀(").append(TIME_HHMM.format(returnTime)).append(") 명단\n");
        sb.append(line).append("\n\n");

        sb.append(" 대상일    : ").append(DATE_KO_FULL.format(payload.date())).append('\n');
        sb.append(" 총 인원   : ").append(payload.totalCount()).append("명\n");
        sb.append(" 복귀 시각 : ").append(TIME_HHMM.format(returnTime)).append(" (출입문 일괄 개방)\n");
        sb.append(" 통금 시각 : ").append(TIME_HHMM.format(curfewTime)).append(" (이후 출입문 잠김)\n\n");

        sb.append("[복귀 명단]\n");
        sb.append(renderFixedWidthTable(payload.rows()));
        sb.append('\n');

        sb.append("[반별 인원]\n");
        Map<String, List<NoticePayload.Row>> grouped = groupByClass(payload.rows());
        if (grouped.isEmpty()) {
            sb.append(" (없음)\n");
        } else {
            List<String> summary = new ArrayList<>(grouped.size());
            for (Map.Entry<String, List<NoticePayload.Row>> entry : grouped.entrySet()) {
                summary.add(entry.getKey() + " " + entry.getValue().size() + "명");
            }
            sb.append(' ').append(String.join(" / ", summary)).append('\n');
        }
        sb.append('\n');

        sb.append("[안내]\n");
        sb.append(" - ").append(TIME_HHMM.format(curfewTime)).append(" 이후 기숙사 출입문은 잠깁니다.\n");
        sb.append(" - 위 명단의 교육생은 ").append(TIME_HHMM.format(returnTime))
                .append("에 출입문이 일괄 개방될 때 함께 입관합니다.\n");
        sb.append(" - 명단에 없는 교육생은 ").append(TIME_HHMM.format(curfewTime))
                .append(" 이전에 복귀해야 합니다.\n\n");

        sb.append("[전체 명단 조회 페이지]\n");
        sb.append(' ').append(payload.lookupUrl()).append('\n');
        sb.append(" (링크에는 열람 토큰이 포함되어 있습니다. 외부에 공유하지 말아 주세요.)\n\n");

        sb.append(line).append('\n');
        sb.append(" 본 메일은 기숙사 야간복귀 등록 시스템에서 자동 발송되었습니다.\n");
        sb.append(line).append('\n');
        return sb.toString();
    }

    /** 이메일 HTML 본문. 인라인 CSS + 모바일 가독성(폰트 15px 이상, 표 max-width 100%). */
    public String emailHtml(NoticePayload payload) {
        LocalTime returnTime = payload.returnTime();
        LocalTime curfewTime = payload.curfewTime();
        String dateText = DATE_KO_FULL.format(payload.date());
        StringBuilder sb = new StringBuilder(8192);

        sb.append("<!DOCTYPE html>\n<html lang=\"ko\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>").append(escape(emailSubject(payload))).append("</title>\n");
        sb.append("</head>\n");
        sb.append("<body style=\"margin:0;padding:0;background-color:#f2f4f7;")
                .append("font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic',")
                .append("'맑은 고딕',AppleGothic,'Noto Sans KR',sans-serif;")
                .append("word-break:keep-all;overflow-wrap:anywhere;\">\n");
        sb.append("<div style=\"max-width:640px;margin:0 auto;padding:16px;\">\n");

        // 헤더 카드
        sb.append("<div style=\"background:#1f3a93;color:#ffffff;border-radius:12px;padding:20px 18px;\">\n");
        sb.append("<div style=\"font-size:15px;opacity:0.9;\">기숙사 야간복귀 명단</div>\n");
        sb.append("<div style=\"font-size:22px;font-weight:700;margin-top:6px;line-height:1.4;\">")
                .append(escape(dateText)).append("</div>\n");
        sb.append("<div style=\"font-size:17px;margin-top:8px;line-height:1.5;\">")
                .append(escape(TIME_HHMM.format(returnTime))).append(" 복귀 · 총 <strong>")
                .append(payload.totalCount()).append("명</strong></div>\n");
        sb.append("</div>\n");

        // 안내 박스
        sb.append("<div style=\"background:#fff8e1;border:1px solid #ffe082;border-radius:12px;")
                .append("padding:14px 16px;margin-top:14px;font-size:15px;line-height:1.7;color:#5d4037;\">\n");
        sb.append("<div><strong>").append(escape(TIME_HHMM.format(curfewTime)))
                .append(" 이후 출입문은 잠깁니다.</strong></div>\n");
        sb.append("<div>아래 명단의 교육생은 ").append(escape(TIME_HHMM.format(returnTime)))
                .append("에 출입문이 <strong>일괄 개방</strong>될 때 함께 입관합니다.</div>\n");
        sb.append("</div>\n");

        // 명단 표
        sb.append("<div style=\"background:#ffffff;border-radius:12px;padding:16px;margin-top:14px;\">\n");
        sb.append("<div style=\"font-size:16px;font-weight:700;color:#1f2937;margin-bottom:10px;\">복귀 명단</div>\n");
        sb.append("<div style=\"overflow-x:auto;\">\n");
        sb.append("<table style=\"width:100%;max-width:100%;border-collapse:collapse;font-size:15px;\">\n");
        sb.append("<thead><tr style=\"background:#eef2ff;\">")
                .append(th("No", "right")).append(th("반", "left"))
                .append(th("이름", "left")).append(th("호수", "left"))
                .append("</tr></thead>\n<tbody>\n");
        if (payload.rows().isEmpty()) {
            sb.append("<tr><td colspan=\"4\" style=\"padding:14px;text-align:center;color:#6b7280;")
                    .append("border-bottom:1px solid #e5e7eb;font-size:15px;\">등록된 인원이 없습니다.</td></tr>\n");
        } else {
            for (NoticePayload.Row row : payload.rows()) {
                sb.append("<tr>")
                        .append(td(String.valueOf(row.no()), "right"))
                        .append(td(row.className(), "left"))
                        .append(td(row.studentName(), "left"))
                        .append(td(row.roomNumber(), "left"))
                        .append("</tr>\n");
            }
        }
        sb.append("</tbody>\n</table>\n</div>\n");

        Map<String, List<NoticePayload.Row>> grouped = groupByClass(payload.rows());
        if (!grouped.isEmpty()) {
            sb.append("<div style=\"margin-top:12px;font-size:15px;color:#374151;line-height:1.7;\">")
                    .append("<strong>반별 인원</strong><br>");
            List<String> summary = new ArrayList<>(grouped.size());
            for (Map.Entry<String, List<NoticePayload.Row>> entry : grouped.entrySet()) {
                summary.add(escape(entry.getKey()) + " " + entry.getValue().size() + "명");
            }
            sb.append(String.join(" · ", summary)).append("</div>\n");
        }
        sb.append("</div>\n");

        // 조회 링크
        sb.append("<div style=\"background:#ffffff;border-radius:12px;padding:16px;margin-top:14px;\">\n");
        sb.append("<div style=\"font-size:16px;font-weight:700;color:#1f2937;margin-bottom:10px;\">")
                .append("전체 명단 조회</div>\n");
        String url = payload.lookupUrl();
        sb.append("<a href=\"").append(escape(url)).append("\" ")
                .append("style=\"display:inline-block;background:#1f3a93;color:#ffffff;text-decoration:none;")
                .append("font-size:16px;font-weight:600;padding:14px 20px;border-radius:10px;\">")
                .append("조회 페이지 열기</a>\n");
        sb.append("<div style=\"margin-top:10px;font-size:14px;color:#6b7280;line-height:1.7;")
                .append("word-break:break-all;\">").append(escape(url)).append("</div>\n");
        sb.append("<div style=\"margin-top:6px;font-size:14px;color:#9ca3af;\">")
                .append("링크에는 열람 토큰이 포함되어 있습니다. 외부에 공유하지 말아 주세요.</div>\n");
        sb.append("</div>\n");

        sb.append("<div style=\"margin-top:16px;font-size:13px;color:#9ca3af;text-align:center;line-height:1.7;\">")
                .append("본 메일은 기숙사 야간복귀 등록 시스템에서 자동 발송되었습니다.</div>\n");
        sb.append("</div>\n</body>\n</html>\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 내부 헬퍼
    // ------------------------------------------------------------------

    /** 반 기준 그룹핑. 입력 순서(반 오름차순 → 이름 오름차순)를 그대로 유지한다. */
    private static Map<String, List<NoticePayload.Row>> groupByClass(List<NoticePayload.Row> rows) {
        Map<String, List<NoticePayload.Row>> grouped = new LinkedHashMap<>();
        for (NoticePayload.Row row : rows) {
            grouped.computeIfAbsent(row.className(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    /** 고정폭 표. 전각(한글) 폭 2를 반영해 열 너비를 계산한 뒤 패딩한다. */
    private static String renderFixedWidthTable(List<NoticePayload.Row> rows) {
        String headNo = "번호";
        String headClass = "반";
        String headName = "이름";
        String headRoom = "호수";
        if (rows.isEmpty()) {
            return " (등록된 인원이 없습니다.)\n";
        }

        int wNo = displayWidth(headNo);
        int wClass = displayWidth(headClass);
        int wName = displayWidth(headName);
        int wRoom = displayWidth(headRoom);
        for (NoticePayload.Row row : rows) {
            wNo = Math.max(wNo, displayWidth(String.valueOf(row.no())));
            wClass = Math.max(wClass, displayWidth(row.className()));
            wName = Math.max(wName, displayWidth(row.studentName()));
            wRoom = Math.max(wRoom, displayWidth(row.roomNumber()));
        }

        StringBuilder sb = new StringBuilder(rows.size() * 48 + 128);
        // 마지막 열은 패딩하지 않는다(줄 끝 공백 방지).
        sb.append(' ').append(padLeft(headNo, wNo)).append("  ")
                .append(padRight(headClass, wClass)).append("  ")
                .append(padRight(headName, wName)).append("  ")
                .append(headRoom).append('\n');
        sb.append(' ').append("-".repeat(wNo)).append("  ")
                .append("-".repeat(wClass)).append("  ")
                .append("-".repeat(wName)).append("  ")
                .append("-".repeat(wRoom)).append('\n');
        for (NoticePayload.Row row : rows) {
            sb.append(' ').append(padLeft(String.valueOf(row.no()), wNo)).append("  ")
                    .append(padRight(row.className(), wClass)).append("  ")
                    .append(padRight(row.studentName(), wName)).append("  ")
                    .append(row.roomNumber()).append('\n');
        }
        return sb.toString();
    }

    /** 표시 폭(전각 2, 반각 1) 합계. */
    private static int displayWidth(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int width = 0;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            width += charWidth(codePoint);
            index += Character.charCount(codePoint);
        }
        return width;
    }

    /** 동아시아 전각(Wide/Fullwidth) 문자면 2, 아니면 1. */
    private static int charWidth(int codePoint) {
        if (codePoint >= 0x1100
                && (codePoint <= 0x115F                                    // 한글 자모 초성
                || codePoint == 0x2329 || codePoint == 0x232A
                || (codePoint >= 0x2E80 && codePoint <= 0xA4CF && codePoint != 0x303F) // CJK 부수 ~ 이체자
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)            // 한글 음절
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)            // CJK 호환 한자
                || (codePoint >= 0xFE30 && codePoint <= 0xFE6F)            // CJK 호환 형태
                || (codePoint >= 0xFF00 && codePoint <= 0xFF60)            // 전각 영숫자
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                || (codePoint >= 0x20000 && codePoint <= 0x2FFFD)
                || (codePoint >= 0x30000 && codePoint <= 0x3FFFD))) {
            return 2;
        }
        return 1;
    }

    private static String padRight(String value, int width) {
        String text = value == null ? "" : value;
        int pad = width - displayWidth(text);
        return pad <= 0 ? text : text + " ".repeat(pad);
    }

    private static String padLeft(String value, int width) {
        String text = value == null ? "" : value;
        int pad = width - displayWidth(text);
        return pad <= 0 ? text : " ".repeat(pad) + text;
    }

    private static String th(String label, String align) {
        return "<th style=\"padding:10px 8px;text-align:" + align + ";font-size:15px;color:#1f2937;"
                + "border-bottom:2px solid #c7d2fe;white-space:nowrap;\">" + escape(label) + "</th>";
    }

    private static String td(String value, String align) {
        return "<td style=\"padding:10px 8px;text-align:" + align + ";font-size:15px;color:#374151;"
                + "border-bottom:1px solid #e5e7eb;\">" + escape(value) + "</td>";
    }

    /** HTML 특수문자 이스케이프(명단은 사용자 입력이므로 반드시 처리한다). */
    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 날짜만 필요한 호출부를 위한 보조 포맷터(외부 모듈에서도 동일 표기를 쓰도록 공개). */
    public String formatKoreanDate(LocalDate date) {
        return DATE_KO_FULL.format(date);
    }
}
