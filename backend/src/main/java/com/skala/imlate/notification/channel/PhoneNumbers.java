package com.skala.imlate.notification.channel;

/**
 * 연락처(전화번호·이메일) 정규화 및 로그 마스킹 유틸.
 *
 * <p>전화번호는 숫자만 남기고 국제 표기({@code +82}, {@code 0082})를 국내 {@code 0} 접두 형태로 바꾼다.
 * 로그에는 개인정보를 그대로 남기지 않도록 마스킹한 값을 사용한다.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
        // 유틸리티 클래스
    }

    /**
     * 전화번호를 숫자만 남긴 국내 표기로 정규화한다. 값이 없으면 빈 문자열.
     *
     * <p>예) {@code "+82-10-1234-5678"} → {@code "01012345678"}
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        String value = digits.toString();
        if (value.isEmpty()) {
            return "";
        }
        // 국제 표기를 국내 표기로 환원한다.
        if (value.startsWith("0082")) {
            value = "0" + value.substring(4);
        } else if (value.startsWith("82") && value.length() >= 11) {
            value = "0" + value.substring(2);
        }
        return value;
    }

    /** 정규화된 번호가 국내 전화번호로 볼 수 있는 형태인지 검사한다. */
    public static boolean isValid(String normalizedPhone) {
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            return false;
        }
        if (!normalizedPhone.startsWith("0")) {
            return false;
        }
        int length = normalizedPhone.length();
        return length >= 9 && length <= 12;
    }

    /** 로그용 전화번호 마스킹. 예) {@code 010****5678} */
    public static String mask(String phone) {
        String value = normalize(phone);
        if (value.isEmpty()) {
            return "(미설정)";
        }
        if (value.length() <= 7) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 3) + "*".repeat(value.length() - 7) + value.substring(value.length() - 4);
    }

    /** 로그용 이메일 마스킹. 예) {@code s***@example.com} */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "(미설정)";
        }
        String value = email.trim();
        int at = value.indexOf('@');
        if (at <= 0) {
            return "*".repeat(Math.min(value.length(), 6));
        }
        String local = value.substring(0, at);
        String domain = value.substring(at);
        if (local.length() <= 1) {
            return "*" + domain;
        }
        return local.charAt(0) + "*".repeat(local.length() - 1) + domain;
    }
}
