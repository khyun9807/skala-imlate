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
 * {@link RegistrationWindowPolicy} 단위 테스트. 고정 시계로 등록·취소 창 경계를 검증한다(SPEC §3).
 *
 * <p>등록 창은 {@code [00:00, 22:15)}, <b>취소 창은 그보다 넓은</b> {@code [00:00, 22:20)} 이다.
 * 취소를 5분 더 열어 두는 이유는 마감 직전에 등록하고 곧바로 마음이 바뀐 사람에게 되돌릴 틈을 주기
 * 위해서다(22:15 등록 마감 → 22:20 취소 마감 → 22:25 발송 → 22:30 통금 → 23:30 일괄 개방).
 *
 * <p><b>이 파일에서 가장 중요한 것은 두 마감 사이 구간(22:15~22:20)의 검증이다.</b>
 * 그 5분 동안 등록은 거부되고 취소는 통과해야 한다. 둘이 같은 판정을 쓰던 시절로 되돌아가면
 * 여기서 잡힌다.
 */
@DisplayName("등록 창 정책(RegistrationWindowPolicy)")
class RegistrationWindowPolicyTest {

    /** 등록 마감 시각(22:15). */
    private static final LocalTime CLOSE_TIME = LocalTime.of(22, 15);

    /** 취소 마감 시각(22:20). 등록 마감보다 5분 늦다. */
    private static final LocalTime CANCEL_CLOSE_TIME = LocalTime.of(22, 20);

    /** 운영 기본값과 같은 창 설정 — 등록 {@code [00:00, 22:15)}, 취소 {@code [00:00, 22:20)}. */
    private static ImlateProperties properties() {
        return TestFixtures.imlateProperties(LocalTime.MIDNIGHT, CLOSE_TIME, CANCEL_CLOSE_TIME);
    }

    private static RegistrationWindowPolicy policyAt(LocalTime now) {
        return new RegistrationWindowPolicy(properties(), TestFixtures.clockAt(now));
    }

    @Test
    @DisplayName("22:14 에는 등록 창이 열려 있고 requireOpen 이 통과한다")
    void 마감_1분_전에는_등록이_가능하다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(22, 14));

        assertThat(policy.isOpen()).isTrue();
        assertThat(policy.targetDate()).isEqualTo(TestFixtures.DATE);
        assertThatCode(policy::requireOpen).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("22:15 정각부터는 등록이 마감된다(REGISTRATION_CLOSED)")
    void 마감_정각에는_등록이_거부된다() {
        RegistrationWindowPolicy policy = policyAt(CLOSE_TIME);

        assertThat(policy.isOpen()).isFalse();
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(policy::requireOpen)
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REGISTRATION_CLOSED));
    }

    @Test
    @DisplayName("22:15 를 지난 22:15:01 에도 마감 상태가 유지된다")
    void 마감_직후에도_거부된다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(22, 15, 1));

        assertThat(policy.isOpen()).isFalse();
        assertThatExceptionOfType(ApiException.class).isThrownBy(policy::requireOpen);
    }

    @Test
    @DisplayName("마감 이후인 22:40 에도 그날 등록은 다시 열리지 않는다")
    void 마감_후에도_닫혀_있다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(22, 40));

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
        ImlateProperties properties =
                TestFixtures.imlateProperties(LocalTime.of(9, 0), CLOSE_TIME, CANCEL_CLOSE_TIME);
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
        ImlateProperties properties =
                TestFixtures.imlateProperties(LocalTime.of(9, 0), CLOSE_TIME, CANCEL_CLOSE_TIME);
        RegistrationWindowPolicy policy =
                new RegistrationWindowPolicy(properties, TestFixtures.clockAt(LocalTime.of(9, 0)));

        assertThat(policy.isOpen()).isTrue();
        assertThatCode(policy::requireOpen).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // 취소 창 — 등록 창과 별도의 마감을 본다
    // ------------------------------------------------------------------

    @Test
    @DisplayName("등록 마감(22:15)과 취소 마감(22:20) 사이에는 등록은 막히고 취소는 열려 있다")
    void 두_마감_사이에는_취소만_가능하다() {
        // 이 5분이 이번 변경의 존재 이유다. 등록·취소가 같은 판정을 쓰면 여기서 깨진다.
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(22, 17));

        assertThat(policy.isOpen()).isFalse();
        assertThat(policy.isCancelOpen()).isTrue();
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(policy::requireOpen)
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REGISTRATION_CLOSED));
        assertThatCode(policy::requireCancelOpen).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("22:19 에는 취소가 가능하다")
    void 취소_마감_1분_전에는_취소가_가능하다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(22, 19));

        assertThat(policy.isCancelOpen()).isTrue();
        assertThatCode(policy::requireCancelOpen).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("22:20 정각부터는 취소도 마감된다(REGISTRATION_CLOSED)")
    void 취소_마감_정각에는_거부된다() {
        RegistrationWindowPolicy policy = policyAt(CANCEL_CLOSE_TIME);

        assertThat(policy.isCancelOpen()).isFalse();
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(policy::requireCancelOpen)
                .satisfies(ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.REGISTRATION_CLOSED);
                    // 등록 마감과 시각이 다르므로 문구가 어느 마감인지 밝혀야 한다.
                    assertThat(ex.getMessage()).contains("취소").contains("22:20");
                });
    }

    @Test
    @DisplayName("개장 전에는 취소도 REGISTRATION_NOT_OPEN 으로 거부된다")
    void 개장_전에는_취소도_열리지_않았다() {
        ImlateProperties properties =
                TestFixtures.imlateProperties(LocalTime.of(9, 0), CLOSE_TIME, CANCEL_CLOSE_TIME);
        RegistrationWindowPolicy policy = new RegistrationWindowPolicy(properties,
                TestFixtures.clockAt(LocalTime.of(8, 59, 59)));

        assertThat(policy.isCancelOpen()).isFalse();
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(policy::requireCancelOpen)
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REGISTRATION_NOT_OPEN));
    }

    @Test
    @DisplayName("취소 마감을 등록 마감보다 이르게 설정하면 등록 마감까지 끌어올린다")
    void 취소_마감이_등록_마감보다_이르면_보정된다() {
        // 잘못 설정해도 "등록은 됐는데 취소는 이미 닫힌" 구간이 생기지 않아야 한다.
        ImlateProperties.Registration registration = new ImlateProperties.Registration(
                LocalTime.MIDNIGHT, LocalTime.of(22, 15), LocalTime.of(21, 0),
                null, null, 0, 0, null);

        assertThat(registration.cancelCloseTime()).isEqualTo(LocalTime.of(22, 15));
    }

    // ------------------------------------------------------------------
    // describe() / 게터 / 기본값
    // ------------------------------------------------------------------

    @Test
    @DisplayName("describe() 는 등록·취소 두 마감을 서버 기준으로 함께 채운다")
    void 등록창_상태를_설명한다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(22, 14));

        RegistrationWindow window = policy.describe();

        assertThat(window.date()).isEqualTo(TestFixtures.DATE);
        assertThat(window.open()).isTrue();
        assertThat(window.secondsUntilClose()).isEqualTo(60L);
        assertThat(window.opensAt().toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(window.closesAt().toLocalTime()).isEqualTo(CLOSE_TIME);
        assertThat(window.serverTime().toLocalTime()).isEqualTo(LocalTime.of(22, 14));
        assertThat(window.returnTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(window.curfewTime()).isEqualTo(LocalTime.of(22, 30));
        // 취소 창은 5분 더 남아 있다(60 + 300초).
        assertThat(window.cancelOpen()).isTrue();
        assertThat(window.cancelClosesAt().toLocalTime()).isEqualTo(CANCEL_CLOSE_TIME);
        assertThat(window.secondsUntilCancelClose()).isEqualTo(360L);
    }

    @Test
    @DisplayName("두 마감 사이의 describe() 는 open=false / cancelOpen=true 로 갈린다")
    void 두_마감_사이의_상태를_설명한다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(22, 17));

        RegistrationWindow window = policy.describe();

        assertThat(window.open()).isFalse();
        assertThat(window.secondsUntilClose()).isZero();
        assertThat(window.cancelOpen()).isTrue();
        assertThat(window.secondsUntilCancelClose()).isEqualTo(180L);
    }

    @Test
    @DisplayName("두 마감이 모두 지나면 describe() 의 남은 초는 둘 다 0 이다")
    void 마감_후_남은_초는_0이다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(22, 30));

        RegistrationWindow window = policy.describe();

        assertThat(window.open()).isFalse();
        assertThat(window.secondsUntilClose()).isZero();
        assertThat(window.cancelOpen()).isFalse();
        assertThat(window.secondsUntilCancelClose()).isZero();
    }

    @Test
    @DisplayName("안내 시각 게터는 설정값(23:30 / 22:30 / 22:15 / 22:20)을 그대로 노출한다")
    void 안내_시각을_노출한다() {
        RegistrationWindowPolicy policy = policyAt(LocalTime.of(12, 0));

        assertThat(policy.returnTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(policy.curfewTime()).isEqualTo(LocalTime.of(22, 30));
        assertThat(policy.closeTime()).isEqualTo(CLOSE_TIME);
        assertThat(policy.cancelCloseTime()).isEqualTo(CANCEL_CLOSE_TIME);
    }

    @Test
    @DisplayName("설정이 비어 있으면 자바 기본값 등록 [00:00, 22:15) / 취소 [00:00, 22:20) 로 보정된다")
    void 설정이_없으면_기본값으로_보정된다() {
        // yml 기본값과 자바 보정값이 어긋나면, 설정을 지운 환경에서 마감 시각이 달라진다.
        ImlateProperties.Registration registration =
                new ImlateProperties.Registration(null, null, null, null, 0, 0);

        assertThat(registration.openTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(registration.closeTime()).isEqualTo(CLOSE_TIME);
        assertThat(registration.cancelCloseTime()).isEqualTo(CANCEL_CLOSE_TIME);
        assertThat(registration.returnTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(registration.curfewTime()).isEqualTo(LocalTime.of(22, 30));
    }
}
