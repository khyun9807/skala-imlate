package com.skala.imlate.notification.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.skala.imlate.common.properties.NotificationProperties;
import com.skala.imlate.registration.service.ReconciliationReport;
import com.skala.imlate.support.TestFixtures;

/**
 * {@link CurfewNoticeRenderer} 단위 테스트.
 *
 * <p>요구사항 R4/R9 에 따라 문자·이메일 본문에 <b>반·이름·호수</b>, 23:30 복귀 안내, 22:30 통금 안내,
 * 조회 페이지 URL 이 반드시 포함되어야 한다. 여기에 <b>수신 전용(답장 불가) 안내와 문의처</b>가 더해진다.
 * 빈 명단에서도 예외 없이 렌더링되어야 한다.
 *
 * <p>반대로 <b>대사(검증) 결과와 이용 통계는 어떤 본문에도 들어가면 안 된다.</b> 값이 채워져 있어도
 * 렌더링에서 제외되는지(회귀 방지) 를 함께 검증한다.
 */
@DisplayName("사감 안내문 렌더러(CurfewNoticeRenderer)")
class CurfewNoticeRendererTest {

    private static final String LOOKUP_URL =
            TestFixtures.LOOKUP_BASE_URL + "/lookup?date=2026-08-05&token=TEST-TOKEN";

    /** 국내 문자 길이 판정 문자셋(렌더러와 동일). */
    private static final Charset EUC_KR = Charset.forName("EUC-KR");
    /** LMS 최대 길이. 이 한도를 넘으면 발송 자체가 실패하거나 잘린다. */
    private static final int LMS_LIMIT_BYTES = 2000;
    /** 기본 문의처(설정 미지정 시). */
    private static final String DEFAULT_CONTACT_NAME = "SKALA 운영진";
    private static final String DEFAULT_CONTACT_EMAIL = "khdev07@naver.com";

    private final CurfewNoticeRenderer renderer = new CurfewNoticeRenderer();

    /** 3개 반에 걸친 12명 샘플 명단(반 → 이름 정렬 순서). */
    private static List<NoticePayload.Row> sampleRows() {
        List<NoticePayload.Row> rows = new ArrayList<>();
        rows.add(new NoticePayload.Row(1, "1", "김가영", "301"));
        rows.add(new NoticePayload.Row(2, "1", "남궁민수", "302"));
        rows.add(new NoticePayload.Row(3, "1", "박준호", "305"));
        rows.add(new NoticePayload.Row(4, "1", "홍길동", "302"));
        rows.add(new NoticePayload.Row(5, "2", "AliceKim", "410"));
        rows.add(new NoticePayload.Row(6, "2", "서지우", "411"));
        rows.add(new NoticePayload.Row(7, "2", "이수민", "412"));
        rows.add(new NoticePayload.Row(8, "2", "정예린", "410"));
        rows.add(new NoticePayload.Row(9, "3", "최민재", "A-201"));
        rows.add(new NoticePayload.Row(10, "3", "한도윤", "A-202"));
        rows.add(new NoticePayload.Row(11, "3", "황보름", "A-203"));
        rows.add(new NoticePayload.Row(12, "3", "Bob Lee", "A-204"));
        return rows;
    }

    private static NoticePayload samplePayload() {
        return new NoticePayload(TestFixtures.DATE, sampleRows(), LOOKUP_URL,
                TestFixtures.RETURN_TIME, TestFixtures.CURFEW_TIME,
                TestFixtures.consistentReport(TestFixtures.DATE, 12L), TestFixtures.stats());
    }

    /** 200명 명단(문자 본문이 반별 요약 모드로 넘어가는 크기). */
    private static NoticePayload manyRowsPayload() {
        List<NoticePayload.Row> many = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            many.add(new NoticePayload.Row(i, (i % 5 + 1) + "반", "교육생" + i, String.valueOf(300 + i)));
        }
        return new NoticePayload(TestFixtures.DATE, many, LOOKUP_URL,
                TestFixtures.RETURN_TIME, TestFixtures.CURFEW_TIME,
                TestFixtures.consistentReport(TestFixtures.DATE, 200L), TestFixtures.stats());
    }

    // ------------------------------------------------------------------
    // 문자
    // ------------------------------------------------------------------

    @Test
    @DisplayName("문자 제목은 날짜·복귀 시각·인원 수를 요약한다")
    void 문자_제목() {
        assertThat(renderer.smsTitle(samplePayload())).isEqualTo("[기숙사] 8/5 23:30 복귀 12명");
    }

    @Test
    @DisplayName("문자 본문에 12명의 반·이름·호수와 23:30 / 22:30 안내, 조회 URL 이 모두 들어간다")
    void 문자_본문에_명단과_안내가_모두_포함된다() {
        NoticePayload payload = samplePayload();

        String body = renderer.smsBody(payload);

        assertThat(body).contains("[기숙사 야간복귀 명단]");
        assertThat(body).contains("8월 5일");
        assertThat(body).contains("12명");

        // 반 · 이름 · 호수
        assertThat(body).contains("1", "2", "3");
        for (NoticePayload.Row row : payload.rows()) {
            assertThat(body).contains(row.studentName());
            assertThat(body).contains(row.roomNumber());
        }

        // 시각 안내(원래 통금 22:30 → 23:30 일괄 개방)
        assertThat(body).contains("22:30");
        assertThat(body).contains("23:30");
        assertThat(body).contains("일괄 개방");

        assertThat(body).contains(LOOKUP_URL);
    }

    @Test
    @DisplayName("문자 본문에는 대사(검증) 결과와 통계가 포함되지 않는다")
    void 문자_본문에_검증과_통계가_없다() {
        // verification/stats 가 채워져 있어도 문구에는 새어 나가면 안 된다.
        String body = renderer.smsBody(samplePayload());

        assertThat(body).doesNotContain("검증");
        assertThat(body).doesNotContain("통계");
        assertThat(body).doesNotContain("WAL");
        assertThat(body).doesNotContain("방문");
    }

    @Test
    @DisplayName("반별 인원 수가 문자 본문에 표기된다")
    void 문자_본문에_반별_인원이_표기된다() {
        String body = renderer.smsBody(samplePayload());

        assertThat(body).contains("· 1반 (4명)");
        assertThat(body).contains("· 2반 (4명)");
        assertThat(body).contains("· 3반 (4명)");
    }

    @Test
    @DisplayName("문자 본문 말미에 수신 전용(답장 불가) 안내와 문의처가 들어간다")
    void 문자_본문에_회신불가와_문의처가_들어간다() {
        String body = renderer.smsBody(samplePayload());

        // 사감이 문자에 답장해도 아무도 읽지 않는다는 사실을 반드시 알려야 한다.
        assertThat(body).contains("수신 전용");
        assertThat(body).contains("답장");
        // 답장 대신 어디로 연락해야 하는지도 같이 준다.
        assertThat(body).contains(DEFAULT_CONTACT_NAME);
        assertThat(body).contains(DEFAULT_CONTACT_EMAIL);
    }

    @Test
    @DisplayName("반별 요약 모드로 전환돼도 수신 전용·문의처 안내는 남는다")
    void 요약_모드에서도_회신불가와_문의처가_남는다() {
        String body = renderer.smsBody(manyRowsPayload());

        assertThat(body).contains("수신 전용");
        assertThat(body).contains(DEFAULT_CONTACT_EMAIL);
    }

    @Test
    @DisplayName("문의 안내를 더해도 문자 본문은 LMS 한도(2000바이트) 안에 머문다")
    void 문자_본문은_LMS_한도_안이다() {
        // 문자는 길이가 곧 비용이다. 안내 두 줄을 붙인 뒤에도 절단 로직이 한도를 지켜야 한다.
        assertThat(renderer.smsBody(samplePayload()).getBytes(EUC_KR).length)
                .isLessThanOrEqualTo(LMS_LIMIT_BYTES);
        assertThat(renderer.smsBody(manyRowsPayload()).getBytes(EUC_KR).length)
                .isLessThanOrEqualTo(LMS_LIMIT_BYTES);
    }

    // ------------------------------------------------------------------
    // 이메일
    // ------------------------------------------------------------------

    @Test
    @DisplayName("이메일 제목에 대상일과 인원 수가 들어간다")
    void 이메일_제목() {
        String subject = renderer.emailSubject(samplePayload());

        assertThat(subject).startsWith("[기숙사 야간복귀]");
        assertThat(subject).contains("8월 5일");
        assertThat(subject).contains("23:30");
        assertThat(subject).contains("12명");
    }

    @Test
    @DisplayName("이메일 텍스트 본문은 고정폭 표에 반·이름·호수를 담고 안내·URL 을 포함한다")
    void 이메일_텍스트_본문() {
        NoticePayload payload = samplePayload();

        String text = renderer.emailText(payload);

        assertThat(text).contains("[복귀 명단]");
        assertThat(text).contains("번호").contains("이름").contains("호수");
        for (NoticePayload.Row row : payload.rows()) {
            assertThat(text).contains(row.className());
            assertThat(text).contains(row.studentName());
            assertThat(text).contains(row.roomNumber());
        }
        assertThat(text).contains("23:30");
        assertThat(text).contains("22:30");
        assertThat(text).contains("[반별 인원]");
        assertThat(text).contains("[안내]");
        assertThat(text).contains(LOOKUP_URL);
    }

    @Test
    @DisplayName("이메일 본문(텍스트·HTML)에는 검증 결과 섹션과 통계 섹션이 없다")
    void 이메일_본문에_검증과_통계_섹션이_없다() {
        NoticePayload payload = samplePayload();

        String text = renderer.emailText(payload);
        String html = renderer.emailHtml(payload);

        assertThat(text).doesNotContain("[검증 결과 - Redis WAL <-> DB 대사]");
        assertThat(text).doesNotContain("[통계]");
        assertThat(text).doesNotContain("WAL");
        assertThat(text).doesNotContain("방문자");

        assertThat(html).doesNotContain("검증 결과");
        assertThat(html).doesNotContain("이용 통계");
        assertThat(html).doesNotContain("WAL");
        assertThat(html).doesNotContain("방문자");
    }

    @Test
    @DisplayName("이메일 본문(텍스트·HTML)에 수신 전용 안내와 문의처가 들어가고 HTML 은 mailto 링크를 건다")
    void 이메일_본문에_회신불가와_문의처가_들어간다() {
        NoticePayload payload = samplePayload();

        String text = renderer.emailText(payload);
        String html = renderer.emailHtml(payload);

        assertThat(text).contains("[문의]");
        assertThat(text).contains("수신 전용");
        assertThat(text).contains("답장");
        assertThat(text).contains(DEFAULT_CONTACT_NAME);
        assertThat(text).contains(DEFAULT_CONTACT_EMAIL);

        assertThat(html).contains("수신 전용");
        assertThat(html).contains("답장");
        assertThat(html).contains(DEFAULT_CONTACT_NAME);
        // 모바일에서 주소를 옮겨 적지 않아도 되도록 mailto: 로 건다.
        assertThat(html).contains("mailto:" + DEFAULT_CONTACT_EMAIL);
    }

    @Test
    @DisplayName("문의처 이름·이메일은 설정값(imlate.notification.contact-*)을 따른다")
    void 문의처는_설정값을_따른다() {
        // 운영진이 바뀌어도 코드를 고치지 않는다 — 설정만 바꾸면 세 본문 모두 따라간다.
        NotificationProperties properties = new NotificationProperties(
                true, "-", "-", 1, 60L, "기숙사 행정실", "office@example.com", List.of());
        CurfewNoticeRenderer configured = new CurfewNoticeRenderer(properties);
        NoticePayload payload = samplePayload();

        String sms = configured.smsBody(payload);
        String text = configured.emailText(payload);
        String html = configured.emailHtml(payload);

        assertThat(sms).contains("기숙사 행정실", "office@example.com").doesNotContain(DEFAULT_CONTACT_EMAIL);
        assertThat(text).contains("기숙사 행정실", "office@example.com").doesNotContain(DEFAULT_CONTACT_EMAIL);
        assertThat(html).contains("기숙사 행정실", "mailto:office@example.com")
                .doesNotContain(DEFAULT_CONTACT_EMAIL);
    }

    @Test
    @DisplayName("이메일 텍스트 본문에는 줄 끝 공백이 없다(고정폭 표 정렬 품질)")
    void 이메일_텍스트에_줄끝_공백이_없다() {
        String text = renderer.emailText(samplePayload());

        for (String line : text.split("\n", -1)) {
            assertThat(line).as("줄 끝 공백: [%s]", line).doesNotEndWith(" ");
        }
    }

    @Test
    @DisplayName("이메일 HTML 본문은 UTF-8 을 명시하고 표·조회 링크·명단을 포함한다")
    void 이메일_HTML_본문() {
        NoticePayload payload = samplePayload();

        String html = renderer.emailHtml(payload);

        assertThat(html).contains("charset=\"UTF-8\"");
        assertThat(html).contains("<table");
        assertThat(html).contains("/lookup?date=2026-08-05");
        assertThat(html).contains("23:30");
        assertThat(html).contains("22:30");
        for (NoticePayload.Row row : payload.rows()) {
            assertThat(html).contains(row.studentName());
            assertThat(html).contains(row.roomNumber());
        }
    }

    @Test
    @DisplayName("이름에 HTML 특수문자가 있어도 이스케이프되어 그대로 삽입되지 않는다")
    void HTML_특수문자는_이스케이프된다() {
        NoticePayload payload = new NoticePayload(TestFixtures.DATE,
                List.of(new NoticePayload.Row(1, "1", "<script>", "302")), LOOKUP_URL,
                TestFixtures.RETURN_TIME, TestFixtures.CURFEW_TIME, null, null);

        String html = renderer.emailHtml(payload);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    // ------------------------------------------------------------------
    // 경계 상황
    // ------------------------------------------------------------------

    @Test
    @DisplayName("빈 명단에서도 세 가지 본문이 예외 없이 렌더링된다")
    void 빈_명단도_안전하게_렌더링된다() {
        NoticePayload empty = new NoticePayload(TestFixtures.DATE, List.of(), LOOKUP_URL,
                TestFixtures.RETURN_TIME, TestFixtures.CURFEW_TIME,
                TestFixtures.consistentReport(TestFixtures.DATE, 0L), TestFixtures.stats());

        assertThatCode(() -> {
            renderer.smsTitle(empty);
            renderer.smsBody(empty);
            renderer.emailSubject(empty);
            renderer.emailText(empty);
            renderer.emailHtml(empty);
        }).doesNotThrowAnyException();

        assertThat(renderer.smsTitle(empty)).isEqualTo("[기숙사] 8/5 23:30 복귀 0명");
        assertThat(renderer.smsBody(empty)).contains("등록된 인원이 없습니다.");
        assertThat(renderer.emailText(empty)).contains("등록된 인원이 없습니다.");
        assertThat(renderer.emailHtml(empty)).contains("등록된 인원이 없습니다.");
    }

    @Test
    @DisplayName("검증·통계 값이 null 이어도 예외 없이 명단과 안내만 렌더링된다")
    void 검증과_통계가_없어도_렌더링된다() {
        NoticePayload payload = new NoticePayload(TestFixtures.DATE, sampleRows(), LOOKUP_URL,
                TestFixtures.RETURN_TIME, TestFixtures.CURFEW_TIME, null, null);

        String sms = renderer.smsBody(payload);
        String text = renderer.emailText(payload);
        String html = renderer.emailHtml(payload);

        // null 이어도 "확인 불가" 같은 문구조차 남기지 않는다(애초에 렌더링 대상이 아니다).
        assertThat(sms).doesNotContain("검증").doesNotContain("통계").contains("홍길동", LOOKUP_URL);
        assertThat(text).doesNotContain("검증").doesNotContain("통계").contains("홍길동", LOOKUP_URL);
        assertThat(html).doesNotContain("검증").doesNotContain("통계").contains("홍길동");
    }

    @Test
    @DisplayName("복구(RECOVERED) 결과도 문자 본문에는 드러나지 않는다")
    void 복구_결과는_문구에_드러나지_않는다() {
        ReconciliationReport recovered = new ReconciliationReport(TestFixtures.DATE,
                ReconciliationReport.STATUS_RECOVERED, 12L, 12L, 1L, List.of(), List.of(),
                TestFixtures.checkedAt(TestFixtures.DATE));
        NoticePayload payload = new NoticePayload(TestFixtures.DATE, sampleRows(), LOOKUP_URL,
                TestFixtures.RETURN_TIME, TestFixtures.CURFEW_TIME, recovered, TestFixtures.stats());

        String body = renderer.smsBody(payload);

        assertThat(body).doesNotContain("복구");
        assertThat(body).doesNotContain("검증");
        // 사감에게 필요한 정보(총원·명단·링크)는 그대로 남는다.
        assertThat(body).contains("12명").contains("홍길동").contains(LOOKUP_URL);
    }

    @Test
    @DisplayName("명단이 200명이면 문자 본문이 반별 요약 모드로 자동 전환된다")
    void 명단이_길면_반별_요약으로_전환된다() {
        String body = renderer.smsBody(manyRowsPayload());

        assertThat(body).contains("200명");
        assertThat(body).contains("명단이 길어");
        assertThat(body).contains(LOOKUP_URL);
        // 요약 모드에서는 개별 이름을 넣지 않는다.
        assertThat(body).doesNotContain("교육생100");
    }
}
