package com.skala.imlate.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.skala.imlate.common.error.ApiException;
import com.skala.imlate.common.error.ErrorCode;
import com.skala.imlate.support.TestFixtures;

/**
 * {@link AccessTokenService} 단위 테스트.
 *
 * <p>토큰은 {@code base64url(exp) + "." + base64url(HMAC-SHA256)} 형식이며,
 * 날짜·서명·만료 중 하나라도 어긋나면 검증에 실패해야 한다(SPEC §4.3).
 */
@DisplayName("조회 토큰 서비스(AccessTokenService)")
class AccessTokenServiceTest {

    /** 발급 시점(22:10)에 고정된 서비스. */
    private final AccessTokenService issuer =
            new AccessTokenService(TestFixtures.imlateProperties(), TestFixtures.clockAt(LocalTime.of(22, 10)));

    @Test
    @DisplayName("발급한 토큰은 같은 날짜로 검증하면 통과한다")
    void 발급한_토큰은_검증에_성공한다() {
        String token = issuer.issue(TestFixtures.DATE);

        assertThat(token).contains(".");
        assertThat(issuer.verify(TestFixtures.DATE, token)).isTrue();
        assertThatCode(() -> issuer.requireValid(TestFixtures.DATE, token)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("다른 날짜로 검증하면 실패한다")
    void 다른_날짜로는_검증에_실패한다() {
        String token = issuer.issue(TestFixtures.DATE);

        assertThat(issuer.verify(TestFixtures.DATE.plusDays(1), token)).isFalse();
        assertThat(issuer.verify(TestFixtures.DATE.minusDays(1), token)).isFalse();
    }

    @Test
    @DisplayName("서명이 위조된 토큰은 검증에 실패한다")
    void 서명이_위조되면_실패한다() {
        String token = issuer.issue(TestFixtures.DATE);
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        assertThat(tampered).isNotEqualTo(token);
        assertThat(issuer.verify(TestFixtures.DATE, tampered)).isFalse();
    }

    @Test
    @DisplayName("다른 시크릿으로 만든 토큰은 검증에 실패한다")
    void 다른_시크릿의_토큰은_실패한다() {
        AccessTokenService attacker = new AccessTokenService(
                TestFixtures.imlatePropertiesWithSecret("완전히-다른-시크릿-9876543210"),
                TestFixtures.clockAt(LocalTime.of(22, 10)));

        String forged = attacker.issue(TestFixtures.DATE);

        assertThat(issuer.verify(TestFixtures.DATE, forged)).isFalse();
    }

    @Test
    @DisplayName("형식이 깨진 토큰(null/빈값/구분자 없음/숫자 아님)은 모두 실패한다")
    void 형식_오류_토큰은_실패한다() {
        assertThat(issuer.verify(TestFixtures.DATE, null)).isFalse();
        assertThat(issuer.verify(TestFixtures.DATE, "")).isFalse();
        assertThat(issuer.verify(TestFixtures.DATE, "   ")).isFalse();
        assertThat(issuer.verify(TestFixtures.DATE, "구분자없음")).isFalse();
        assertThat(issuer.verify(TestFixtures.DATE, ".")).isFalse();
        assertThat(issuer.verify(TestFixtures.DATE, "AAAA.BBBB")).isFalse();
        assertThat(issuer.verify(null, issuer.issue(TestFixtures.DATE))).isFalse();
    }

    @Test
    @DisplayName("만료된 토큰(TTL 48시간 경과)은 검증에 실패한다")
    void 만료된_토큰은_실패한다() {
        String token = issuer.issue(TestFixtures.DATE);

        // 같은 시크릿·같은 TTL 이지만 시계만 3일 뒤로 옮긴 검증기
        AccessTokenService later = new AccessTokenService(TestFixtures.imlateProperties(),
                TestFixtures.clockAt(TestFixtures.DATE.plusDays(3), LocalTime.of(22, 10)));

        assertThat(later.verify(TestFixtures.DATE, token)).isFalse();
    }

    @Test
    @DisplayName("TTL 이 남아 있으면(47시간 경과) 여전히 유효하다")
    void 만료_전_토큰은_여전히_유효하다() {
        String token = issuer.issue(TestFixtures.DATE);

        AccessTokenService later = new AccessTokenService(TestFixtures.imlateProperties(),
                TestFixtures.clockAt(TestFixtures.DATE.plusDays(1), LocalTime.of(21, 10)));

        assertThat(later.verify(TestFixtures.DATE, token)).isTrue();
    }

    @Test
    @DisplayName("requireValid 는 검증 실패 시 FORBIDDEN 예외를 던진다")
    void 검증_실패시_FORBIDDEN_예외를_던진다() {
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> issuer.requireValid(TestFixtures.DATE, "invalid-token"))
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> issuer.requireValid(TestFixtures.DATE, null))
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
