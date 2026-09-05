package com.skala.imlate.notification.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.skala.imlate.support.TestFixtures;

/**
 * 사감 선생님께 나가는 문자·메일에 <b>서비스 종료 안내가 실제로 실리는지</b> 못 박는다.
 *
 * <p>조회 페이지에도 같은 안내를 띄웠지만 그건 링크를 눌러야 보인다. 반면 이 문자·메일은
 * 매일 밤 반드시 받으시는 것이라, 남은 기간 동안 확실히 도달하는 유일한 경로다.
 * 누군가 템플릿을 정리하다 이 문구를 지우면 사감님은 종료를 모른 채 9/8 을 맞는다.
 */
@DisplayName("사감 발송 문구의 서비스 종료 안내")
class ServiceEndNoticeRenderTest {

    private static final String LOOKUP_URL =
            "https://skala-imlate.link/lookup?date=2026-08-05&token=abc.def";

    private final CurfewNoticeRenderer renderer = new CurfewNoticeRenderer();

    private static NoticePayload payload() {
        List<NoticePayload.Row> rows = List.of(
                new NoticePayload.Row(1, "1", "홍길동", "302"),
                new NoticePayload.Row(2, "2", "김가영", "410"));
        return new NoticePayload(TestFixtures.DATE, rows, LOOKUP_URL,
                TestFixtures.RETURN_TIME, TestFixtures.CURFEW_TIME,
                TestFixtures.consistentReport(TestFixtures.DATE, 2L), TestFixtures.stats());
    }

    @Test
    @DisplayName("문자에 종료일과 '명단이 발송되지 않는다'가 들어간다")
    void 문자에_종료_안내가_실린다() {
        String sms = renderer.smsBody(payload());

        assertThat(sms).contains("9/7");
        assertThat(sms).contains("9/8");
        assertThat(sms).contains("종료");
        // 사감이 알아야 하는 실질적 변화는 "명단이 더는 오지 않는다"는 것이다.
        assertThat(sms).contains("발송되지 않는다");
    }

    @Test
    @DisplayName("이메일 본문에 종료일과 사유가 들어간다")
    void 이메일에_종료_안내가_실린다() {
        String text = renderer.emailText(payload());

        assertThat(text).contains("[서비스 종료 안내]");
        assertThat(text).contains("2026년 9월 7일");
        assertThat(text).contains("9월 8일");
        assertThat(text).contains("비용");
    }

    @Test
    @DisplayName("HTML 메일에도 같은 안내가 카드로 들어간다")
    void html_에도_종료_안내가_실린다() {
        String html = renderer.emailHtml(payload());

        assertThat(html).contains("서비스 종료 안내");
        assertThat(html).contains("2026년 9월 7일");
    }
}
