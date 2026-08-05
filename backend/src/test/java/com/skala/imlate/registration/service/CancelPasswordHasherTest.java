package com.skala.imlate.registration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.skala.imlate.common.properties.ImlateProperties;
import com.skala.imlate.support.TestFixtures;

/**
 * {@link CancelPasswordHasher} 단위 테스트.
 *
 * <p>취소 비밀번호는 숫자 4자리 — 경우의 수가 1만 개뿐이라 저장 방식이 곧 방어선이다.
 * 여기서 못 박는 성질은 세 가지다.
 * <ul>
 *   <li>같은 비밀번호라도 저장값이 매번 달라야 한다(salt). 같으면 DB 만 훑어도
 *       "비밀번호가 같은 사람들"이 드러난다.</li>
 *   <li>서버 시크릿(pepper)이 다르면 검증이 실패해야 한다. 이게 성립해야 DB 만 유출됐을 때
 *       대입이 불가능하다.</li>
 *   <li>깨진 값·빈 값에 예외를 던지지 않고 "불일치"로 답해야 한다.
 *       예외가 새면 응답 차이로 그 행의 상태가 드러난다.</li>
 * </ul>
 */
@DisplayName("취소 비밀번호 해시(CancelPasswordHasher)")
class CancelPasswordHasherTest {

    private final CancelPasswordHasher hasher = TestFixtures.cancelPasswordHasher();

    @Test
    @DisplayName("해시한 비밀번호는 원문으로 검증된다")
    void 해시하고_검증한다() {
        String stored = hasher.hash("4821");

        assertThat(hasher.matches("4821", stored)).isTrue();
    }

    @Test
    @DisplayName("다른 비밀번호는 검증에 실패한다")
    void 다른_비밀번호는_실패한다() {
        String stored = hasher.hash("4821");

        assertThat(hasher.matches("4822", stored)).isFalse();
        assertThat(hasher.matches("1234", stored)).isFalse();
        assertThat(hasher.matches("482", stored)).isFalse();
        assertThat(hasher.matches("48210", stored)).isFalse();
    }

    @Test
    @DisplayName("같은 비밀번호라도 저장값은 매번 다르다(salt 가 매번 새로 뽑힌다)")
    void 같은_비밀번호라도_저장값이_다르다() {
        String first = hasher.hash("1111");
        String second = hasher.hash("1111");

        assertThat(first).isNotEqualTo(second);
        // 그래도 둘 다 원문으로 검증된다.
        assertThat(hasher.matches("1111", first)).isTrue();
        assertThat(hasher.matches("1111", second)).isTrue();
    }

    @Test
    @DisplayName("저장 형식은 알고리즘·반복횟수를 함께 담는다")
    void 저장_형식이_자기_서술적이다() {
        String stored = hasher.hash("1234");

        assertThat(stored.split("\\$")).hasSize(4);
        assertThat(stored).startsWith(
                "pbkdf2-sha256$" + ImlateProperties.Cancel.DEFAULT_HASH_ITERATIONS + "$");
    }

    @Test
    @DisplayName("반복 횟수를 올려도 그 전에 저장된 해시는 계속 검증된다")
    void 반복_횟수를_올려도_기존_해시가_검증된다() {
        // 인스턴스를 키운 뒤 hash-iterations 를 올리는 상황. 이때 기존 등록분이 검증되지 않으면
        // 그날 등록한 사람들이 아무도 취소할 수 없게 된다 — 설정 변경이 곧 장애가 된다.
        CancelPasswordHasher weak = hasherWithIterations(1_000);
        String storedWithOldSetting = weak.hash("1234");

        CancelPasswordHasher strong = hasherWithIterations(30_000);

        // 검증은 저장된 문자열에 적힌 반복 횟수를 따르므로 새 설정과 무관하게 성공해야 한다.
        assertThat(strong.matches("1234", storedWithOldSetting)).isTrue();
        // 새로 만드는 해시에는 새 설정이 적용된다.
        assertThat(strong.hash("1234")).startsWith("pbkdf2-sha256$30000$");
    }

    private static CancelPasswordHasher hasherWithIterations(int iterations) {
        ImlateProperties base = TestFixtures.imlateProperties();
        ImlateProperties.Registration reg = base.registration();
        return new CancelPasswordHasher(new ImlateProperties(
                base.timezone(),
                new ImlateProperties.Registration(reg.openTime(), reg.closeTime(), reg.returnTime(),
                        reg.curfewTime(), reg.maxNameLength(), reg.maxRoomLength(),
                        new ImlateProperties.Cancel(4, 10, iterations)),
                base.wal(), base.lookup(), base.admin()));
    }

    @Test
    @DisplayName("서버 시크릿(pepper)이 다르면 검증에 실패한다 — DB 만 유출되면 대입할 수 없다")
    void pepper_가_다르면_검증에_실패한다() {
        String stored = hasher.hash("1234");

        CancelPasswordHasher other =
                new CancelPasswordHasher(TestFixtures.imlatePropertiesWithSecret("완전히-다른-시크릿"));

        // 원문을 알아도 pepper 가 다르면 맞출 수 없다. 이게 4자리를 지켜 주는 실질적인 방어다.
        assertThat(other.matches("1234", stored)).isFalse();
    }

    @Test
    @DisplayName("저장된 값이 없거나 깨져 있으면 예외 대신 불일치로 답한다")
    void 깨진_저장값은_불일치다() {
        assertThat(hasher.matches("1234", null)).isFalse();
        assertThat(hasher.matches("1234", "")).isFalse();
        assertThat(hasher.matches("1234", "   ")).isFalse();
        assertThat(hasher.matches("1234", "평문그대로")).isFalse();
        assertThat(hasher.matches("1234", "bcrypt$10$salt$hash")).isFalse();
        assertThat(hasher.matches("1234", "pbkdf2-sha256$abc$!!!$!!!")).isFalse();
    }

    @Test
    @DisplayName("입력 비밀번호가 비어 있으면 불일치로 답한다")
    void 빈_입력은_불일치다() {
        String stored = hasher.hash("1234");

        assertThat(hasher.matches(null, stored)).isFalse();
        assertThat(hasher.matches("", stored)).isFalse();
    }

    @Test
    @DisplayName("빈 비밀번호는 해시할 수 없다(호출부 실수를 조용히 넘기지 않는다)")
    void 빈_비밀번호는_해시할_수_없다() {
        assertThatThrownBy(() -> hasher.hash(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hash("")).isInstanceOf(IllegalArgumentException.class);
    }
}
