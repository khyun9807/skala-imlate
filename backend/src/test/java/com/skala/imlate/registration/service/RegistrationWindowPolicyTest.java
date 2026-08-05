package com.skala.imlate.registration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.skala.imlate.common.error.ApiException;
import com.skala.imlate.common.error.ErrorCode;
import com.skala.imlate.common.properties.ImlateProperties;
import com.skala.imlate.support.TestFixtures;

/**
 * {@link RegistrationWindowPolicy} 단위 테스트. 고정 시계로 등록 창 경계를 검증한다(SPEC §3).
 */
@DisplayName("등록 창 정책(RegistrationWindowPolicy)")
class RegistrationWindowPolicyTest {

    private static RegistrationWindowPolicy policyAt(LocalTime now) {
        return new RegistrationWindowPolicy(TestFixtures.imlateProperties(), TestFixtures.clockAt(now));
    }

    @Test
    @DisplayName("21:59 에는 등록 창이 열려 있고 requireOpen 이 통과한다")
    void 마감_1분_전에는_등록이_가능하다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(21, 59));

        assertThat(policy.isOpen()).isTrue();
        assertThat(policy.targetDate()).isEqualTo(TestFixtures.DATE);
        assertThatCode(policy::requireOpen).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("22:00 정각부터는 등록이 마감된다(REGISTRATION_CLOSED)")
    void 마감_정각에는_등록이_거부된다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(22, 0));

        assertThat(policy.isOpen()).isFalse();
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(policy::requireOpen)
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REGISTRATION_CLOSED));
    }

    @Test
    @DisplayName("22:00 을 지난 22:00:01 에도 마감 상태가 유지된다")
    void 마감_직후에도_거부된다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(22, 0, 1));

        assertThat(policy.isOpen()).isFalse();
        assertThatExceptionOfType(ApiException.class).isThrownBy(policy::requireOpen);
    }

    @Test
    @DisplayName("개장 시각 이전에는 REGISTRATION_NOT_OPEN 으로 거부된다")
    void 개장_전에는_아직_열리지_않았다() {
        ImlateProperties properties = TestFixtures.imlateProperties(LocalTime.of(9, 0), LocalTime.of(22, 0));
        Clock clock = TestFixtures.clockAt(LocalTime.of(8, 59, 59));
        RegistrationWindowPolicy policy = new RegistrationWindowPolicy(properties, clock);

        assertThat(policy.isOpen()).isFalse();
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(policy::requireOpen)
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REGISTRATION_NOT_OPEN));
    }

    @Test
    @DisplayName("개장 시각 정각(포함)에는 등록이 가능하다")
    void 개장_정각에는_열려_있다() {
        ImlateProperties properties = TestFixtures.imlateProperties(LocalTime.of(9, 0), LocalTime.of(22, 0));
        RegistrationWindowPolicy policy =
                new RegistrationWindowPolicy(properties, TestFixtures.clockAt(LocalTime.of(9, 0)));

        assertThat(policy.isOpen()).isTrue();
        assertThatCode(policy::requireOpen).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("describe() 는 서버 시각·마감 시각·남은 초를 서버 기준으로 채운다")
    void 등록창_상태를_설명한다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(21, 59));

        RegistrationWindow window = policy.describe();

        assertThat(window.date()).isEqualTo(TestFixtures.DATE);
        assertThat(window.open()).isTrue();
        assertThat(window.secondsUntilClose()).isEqualTo(60L);
        assertThat(window.opensAt().toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(window.closesAt().toLocalTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(window.serverTime().toLocalTime()).isEqualTo(LocalTime.of(21, 59));
        assertThat(window.returnTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(window.curfewTime()).isEqualTo(LocalTime.of(22, 30));
    }

    @Test
    @DisplayName("마감 후 describe() 의 남은 초는 0 이고 open 은 false 다")
    void 마감_후_남은_초는_0이다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(22, 30));

        RegistrationWindow window = policy.describe();

        assertThat(window.open()).isFalse();
        assertThat(window.secondsUntilClose()).isZero();
    }

    @Test
    @DisplayName("안내 시각 게터는 설정값(23:30 / 22:30 / 22:00)을 그대로 노출한다")
    void 안내_시각을_노출한다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(12, 0));

        assertThat(policy.returnTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(policy.curfewTime()).isEqualTo(LocalTime.of(22, 30));
        assertThat(policy.closeTime()).isEqualTo(LocalTime.of(22, 0));
    }
}
