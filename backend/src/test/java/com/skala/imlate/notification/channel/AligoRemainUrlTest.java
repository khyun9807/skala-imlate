package com.skala.imlate.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 알리고 잔여 건수 조회 URL 유도 규칙 테스트.
 *
 * <p>발송 URL 은 설정값({@code imlate.sms.aligo.api-url})이므로 잔액 URL 을 따로 설정으로 두면
 * 두 값이 어긋날 수 있다. 발송 URL 의 마지막 경로만 바꿔 항상 같은 호스트를 보게 한다.
 */
@DisplayName("알리고 잔여 건수 URL 유도")
class AligoRemainUrlTest {

    @Test
    @DisplayName("발송 URL 의 마지막 경로를 remain 으로 바꾼다")
    void 발송_URL에서_유도한다() {
        assertThat(AligoSmsSender.remainUrl("https://apis.aligo.in/send/"))
                .isEqualTo("https://apis.aligo.in/remain/");
        // 끝의 슬래시가 없어도 같은 결과여야 한다.
        assertThat(AligoSmsSender.remainUrl("https://apis.aligo.in/send"))
                .isEqualTo("https://apis.aligo.in/remain/");
    }

    @Test
    @DisplayName("경로가 없거나 값이 비면 기본 엔드포인트를 쓴다")
    void 유도할_수_없으면_기본값을_쓴다() {
        assertThat(AligoRemainUrlTest.remain(null)).isEqualTo("https://apis.aligo.in/remain/");
        assertThat(AligoRemainUrlTest.remain("")).isEqualTo("https://apis.aligo.in/remain/");
        assertThat(AligoRemainUrlTest.remain("https://apis.aligo.in")).isEqualTo("https://apis.aligo.in/remain/");
    }

    @Test
    @DisplayName("잔액 판정은 SMS·LMS 중 적은 쪽을 쓴다")
    void 잔액은_적은_쪽을_본다() {
        assertThat(new SmsBalance(6329, 2109, 0).effectiveRemaining()).isEqualTo(2109);
        assertThat(new SmsBalance(12, 500, 0).effectiveRemaining()).isEqualTo(12);
        assertThat(new SmsBalance(6329, 2109, 0).describe()).isEqualTo("SMS 6329건 / LMS 2109건");
    }

    private static String remain(String sendUrl) {
        return AligoSmsSender.remainUrl(sendUrl);
    }
}
