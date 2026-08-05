package com.skala.imlate.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NumericTextOrder} 단위 테스트.
 *
 * <p>반·호수는 DB 에 문자열로 저장되므로 그냥 정렬하면 사전순이다.
 * 반이 9개까지는 사전순과 숫자순이 같아서 아무 문제가 없다가,
 * <b>10개가 되는 순간</b> 사감 명단이 1 → 10 → 11 → 2 순으로 조용히 뒤집힌다.
 * 그 경계를 테스트로 못 박는다.
 */
@DisplayName("숫자 우선 정렬(NumericTextOrder)")
class NumericTextOrderTest {

    private static List<String> sorted(String... values) {
        List<String> list = new ArrayList<>(List.of(values));
        list.sort(NumericTextOrder.INSTANCE);
        return list;
    }

    @Test
    @DisplayName("두 자리 수가 한 자리 수보다 뒤에 온다 (사전순이면 여기서 뒤집힌다)")
    void 자릿수가_달라도_숫자_크기순이다() {
        assertThat(sorted("10", "2", "1", "11", "3"))
                .containsExactly("1", "2", "3", "10", "11");
    }

    @Test
    @DisplayName("호수도 같은 규칙을 따른다 (302 < 1002)")
    void 호수도_숫자_크기순이다() {
        assertThat(sorted("1002", "302", "101", "1204"))
                .containsExactly("101", "302", "1002", "1204");
    }

    @Test
    @DisplayName("숫자가 아닌 값은 숫자 뒤에 오고, 자기들끼리는 사전순이다")
    void 숫자가_아닌_값은_뒤로_간다() {
        // 규칙이 바뀌기 전에 저장된 "1반" 같은 값이 섞여 들어와도 정렬이 무너지지 않아야 한다.
        assertThat(sorted("2반", "10", "1반", "2"))
                .containsExactly("2", "10", "1반", "2반");
    }

    @Test
    @DisplayName("숫자 값이 같아도 표기가 다르면 하나로 합치지 않는다 (TreeMap 키 보존)")
    void 앞자리_0_은_다른_키로_남는다() {
        assertThat(NumericTextOrder.INSTANCE.compare("302", "0302")).isNotZero();
    }

    @Test
    @DisplayName("빈 값·null·아주 긴 숫자에도 예외를 던지지 않는다")
    void 이상한_값에도_깨지지_않는다() {
        assertThat(sorted("", "5", "99999999999999999999", "1"))
                .containsExactly("1", "5", "", "99999999999999999999");
        assertThat(NumericTextOrder.INSTANCE.compare(null, "1")).isPositive();
        assertThat(NumericTextOrder.INSTANCE.compare("1", null)).isNegative();
        assertThat(NumericTextOrder.INSTANCE.compare(null, null)).isZero();
    }

    @Test
    @DisplayName("같은 값은 0 을 돌려준다")
    void 같은_값은_0() {
        assertThat(NumericTextOrder.INSTANCE.compare("7", "7")).isZero();
    }
}
