package com.skala.imlate.notification.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.skala.imlate.notification.channel.SmsBalance;
import com.skala.imlate.notification.channel.SmsSender;
import com.skala.imlate.support.TestFixtures;

/**
 * {@link SmsBalanceMonitor} 단위 테스트(위험 3).
 *
 * <p>핵심 요구: 임계값 미만이면 알리고, <b>하루 1회만 조회하며</b>, 조회 실패는 무시한다.
 */
@DisplayName("문자 잔액 감시(SmsBalanceMonitor)")
class SmsBalanceMonitorTest {

    private SmsSender smsSender;

    @BeforeEach
    void setUp() {
        smsSender = mock(SmsSender.class);
        when(smsSender.providerName()).thenReturn("aligo");
    }

    private SmsBalanceMonitor monitor(int threshold) {
        return new SmsBalanceMonitor(smsSender, threshold);
    }

    @Test
    @DisplayName("잔액이 임계값 미만이면 경고 문구를 돌려준다")
    void 임계값_미만이면_경고한다() {
        when(smsSender.remainingBalance()).thenReturn(Optional.of(new SmsBalance(80, 40, 10)));

        Optional<String> warning = monitor(100).checkOnce(TestFixtures.DATE);

        assertThat(warning).isPresent();
        assertThat(warning.get()).contains("SMS 80건 / LMS 40건", "100건");
    }

    @Test
    @DisplayName("잔액이 넉넉하면 경고하지 않는다")
    void 잔액이_넉넉하면_경고하지_않는다() {
        when(smsSender.remainingBalance()).thenReturn(Optional.of(new SmsBalance(6329, 2109, 0)));

        assertThat(monitor(100).checkOnce(TestFixtures.DATE)).isEmpty();
    }

    @Test
    @DisplayName("LMS 가 부족하면 SMS 가 넉넉해도 경고한다(명단 문자는 대부분 LMS 로 나간다)")
    void 둘_중_적은_쪽을_기준으로_본다() {
        when(smsSender.remainingBalance()).thenReturn(Optional.of(new SmsBalance(6329, 12, 0)));

        assertThat(monitor(100).checkOnce(TestFixtures.DATE)).isPresent();
    }

    @Test
    @DisplayName("같은 날에는 두 번째 호출부터 다시 조회하지 않는다(재시도마다 외부 호출을 늘리지 않는다)")
    void 하루에_한_번만_조회한다() {
        when(smsSender.remainingBalance()).thenReturn(Optional.of(new SmsBalance(10, 10, 0)));
        SmsBalanceMonitor monitor = monitor(100);

        Optional<String> first = monitor.checkOnce(TestFixtures.DATE);
        Optional<String> second = monitor.checkOnce(TestFixtures.DATE);
        Optional<String> third = monitor.checkOnce(TestFixtures.DATE);

        verify(smsSender, times(1)).remainingBalance();
        // 재시도 알림에도 같은 경고가 그대로 붙어야 한다.
        assertThat(first).isPresent();
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    @Test
    @DisplayName("날짜가 바뀌면 다시 조회한다")
    void 날짜가_바뀌면_다시_조회한다() {
        when(smsSender.remainingBalance()).thenReturn(Optional.of(new SmsBalance(10, 10, 0)));
        SmsBalanceMonitor monitor = monitor(100);

        monitor.checkOnce(TestFixtures.DATE);
        monitor.checkOnce(TestFixtures.DATE.plusDays(1));

        verify(smsSender, times(2)).remainingBalance();
    }

    @Test
    @DisplayName("임계값이 0 이하면 조회 자체를 하지 않는다(감시 끄기)")
    void 임계값이_0이하면_조회하지_않는다() {
        assertThat(monitor(0).checkOnce(TestFixtures.DATE)).isEmpty();

        verifyNoInteractions(smsSender);
    }

    @Test
    @DisplayName("조회를 지원하지 않는 발송기(noop)면 아무 일도 하지 않는다")
    void 조회를_지원하지_않으면_넘어간다() {
        when(smsSender.remainingBalance()).thenReturn(Optional.empty());

        assertThat(monitor(100).checkOnce(TestFixtures.DATE)).isEmpty();
    }

    @Test
    @DisplayName("잔액 조회가 예외를 던져도 삼키고 발송 흐름에 영향을 주지 않는다")
    void 조회_예외를_흡수한다() {
        when(smsSender.remainingBalance()).thenThrow(new IllegalStateException("Aligo 장애"));

        assertThatCode(() -> assertThat(monitor(100).checkOnce(TestFixtures.DATE)).isEmpty())
                .doesNotThrowAnyException();
    }
}
