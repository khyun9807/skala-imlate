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
 *
 * <p>등록 창은 {@code [00:00, 21:45)} 이다. 마감을 21:45 로 앞당긴 이유는 21:50 사감 발송 전에
 * 명단을 확정할 5분을 두기 위해서다(21:45 마감 → 21:50 발송 → 22:30 통금 → 23:30 일괄 개방).
 */
@DisplayName("등록 창 정책(RegistrationWindowPolicy)")
class RegistrationWindowPolicyTest {

    /** 등록 마감 시각(21:45). */
    private static final LocalTime CLOSE_TIME = LocalTime.of(21, 45);

    /** 운영 기본값과 같은 등록 창 {@code [00:00, 21:45)} 설정. */
    private static ImlateProperties properties() {
        return TestFixtures.imlateProperties(LocalTime.MIDNIGHT, CLOSE_TIME);
    }

    private static RegistrationWindowPolicy policyAt(LocalTime now) {
        return new RegistrationWindowPolicy(properties(), TestFixtures.clockAt(now));
    }

    @Test
    @DisplayName("21:44 에는 등록 창이 열려 있고 requireOpen 이 통과한다")
    void 마감_1분_전에는_등록이_가능하다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(21, 44));

        assertThat(policy.isOpen()).isTrue();
        assertThat(policy.targetDate()).isEqualTo(TestFixtures.DATE);
        assertThatCode(policy::requireOpen).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("21:45 정각부터는 등록이 마감된다(REGISTRATION_CLOSED)")
    void 마감_정각에는_등록이_거부된다() {
        RegistrationWindowPolicy policy = policyAt(CLOSE_TIME);

        assertThat(policy.isOpen()).isFalse();
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(policy::requireOpen)
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REGISTRATION_CLOSED));
    }

    @Test
    @DisplayName("21:45 를 지난 21:45:01 에도 마감 상태가 유지된다")
    void 마감_직후에도_거부된다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(21, 45, 1));

        assertThat(policy.isOpen()).isFalse();
        assertThatExceptionOfType(ApiException.class).isThrownBy(policy::requireOpen);
    }

    @Test
    @DisplayName("마감 이후인 22:00 에도 그날 등록은 다시 열리지 않는다")
    void 마감_후_22시에도_닫혀_있다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(22, 0));

        assertThat(policy.isOpen()).isFalse();
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(policy::requireOpen)
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REGISTRATION_CLOSED));
    }

    @Test
    @DisplayName("자정(00:00)에는 다음 날 대상 등록이 다시 열린다")
    void 자정에는_다시_열린다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.MIDNIGHT);

        assertThat(policy.isOpen()).isTrue();
        assertThatCode(policy::requireOpen).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("개장 시각 이전에는 REGISTRATION_NOT_OPEN 으로 거부된다")
    void 개장_전에는_아직_열리지_않았다() {
        ImlateProperties properties = TestFixtures.imlateProperties(LocalTime.of(9, 0), CLOSE_TIME);
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
        ImlateProperties properties = TestFixtures.imlateProperties(LocalTime.of(9, 0), CLOSE_TIME);
        RegistrationWindowPolicy policy =
                new RegistrationWindowPolicy(properties, TestFixtures.clockAt(LocalTime.of(9, 0)));

        assertThat(policy.isOpen()).isTrue();
        assertThatCode(policy::requireOpen).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("describe() 는 서버 시각·마감 시각·남은 초를 서버 기준으로 채운다")
    void 등록창_상태를_설명한다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(21, 44));

        RegistrationWindow window = policy.describe();

        assertThat(window.date()).isEqualTo(TestFixtures.DATE);
        assertThat(window.open()).isTrue();
        assertThat(window.secondsUntilClose()).isEqualTo(60L);
        assertThat(window.opensAt().toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(window.closesAt().toLocalTime()).isEqualTo(CLOSE_TIME);
        assertThat(window.serverTime().toLocalTime()).isEqualTo(LocalTime.of(21, 44));
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
    @DisplayName("안내 시각 게터는 설정값(23:30 / 22:30 / 21:45)을 그대로 노출한다")
    void 안내_시각을_노출한다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(12, 0));

        assertThat(policy.returnTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(policy.curfewTime()).isEqualTo(LocalTime.of(22, 30));
        assertThat(policy.closeTime()).isEqualTo(CLOSE_TIME);
    }

    @Test
    @DisplayName("설정이 비어 있으면 자바 기본값 [00:00, 21:45) 로 보정된다")
    void 설정이_없으면_기본값으로_보정된다() {
        // yml 기본값(21:45)과 자바 보정값이 어긋나면, 설정을 지운 환경에서 마감 시각이 달라진다.
        ImlateProperties.Registration registration =
                new ImlateProperties.Registration(null, null, null, null, 0, 0);

        assertThat(registration.openTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(registration.closeTime()).isEqualTo(CLOSE_TIME);
        assertThat(registration.returnTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(registration.curfewTime()).isEqualTo(LocalTime.of(22, 30));
    }
}
