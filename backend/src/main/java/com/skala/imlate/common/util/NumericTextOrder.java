package com.skala.imlate.common.util;

import java.util.Comparator;

/**
 * 숫자로 읽히는 문자열을 <b>숫자 크기 순</b>으로 정렬하는 비교자.
 *
 * <p><b>왜 필요한가</b> — 반·기숙사 호수는 DB 에 {@code VARCHAR} 로 저장된다.
 * 문자열을 그대로 정렬하면 사전순이라 {@code "10" < "2"} 가 되어,
 * 사감이 받는 명단이 <u>1반 → 10반 → 11반 → 2반</u> 순으로 나온다.
 * 반이 9개까지는 티가 안 나다가 10개가 되는 순간 조용히 뒤죽박죽이 된다.
 *
 * <p>숫자가 아닌 값(이 규칙이 적용되기 전에 저장된 {@code "1반"} 같은 값)도 섞일 수 있으므로,
 * 숫자끼리는 크기 순, 숫자와 비숫자가 만나면 숫자를 앞에, 비숫자끼리는 사전순으로 둔다.
 * 어떤 입력에도 예외를 던지지 않는다 — 정렬이 실패해 명단이 통째로 안 나가는 편이 훨씬 나쁘다.
 */
public final class NumericTextOrder {

    /** 정수로 읽을 수 있는 최대 자릿수. 그 이상은 오버플로 대신 문자열 비교로 넘긴다. */
    private static final int MAX_NUMERIC_DIGITS = 9;

    /**
     * 숫자 우선 비교자.
     *
     * <p>숫자 값이 같아도 문자열이 다르면({@code "302"} vs {@code "0302"}) 0 을 돌려주지 않는다.
     * {@code TreeMap} 키로 쓸 때 서로 다른 값이 하나로 합쳐지는 것을 막기 위해서다.
     */
    public static final Comparator<String> INSTANCE = (left, right) -> {
        if (left == null || right == null) {
            return left == null ? (right == null ? 0 : 1) : -1;
        }
        Integer a = toIntOrNull(left);
        Integer b = toIntOrNull(right);
        if (a != null && b != null) {
            int numeric = Integer.compare(a, b);
            return numeric != 0 ? numeric : left.compareTo(right);
        }
        if (a != null) {
            return -1;
        }
        if (b != null) {
            return 1;
        }
        return left.compareTo(right);
    };

    private NumericTextOrder() {
        // 유틸리티 클래스
    }

    /** 전부 숫자면 정수로, 아니면 null. 자릿수가 너무 크면 null(문자열 비교로 넘긴다). */
    private static Integer toIntOrNull(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NUMERIC_DIGITS) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) < '0' || trimmed.charAt(i) > '9') {
                return null;
            }
        }
        return Integer.valueOf(trimmed);
    }
}
