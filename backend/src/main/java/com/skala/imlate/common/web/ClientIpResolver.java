package com.skala.imlate.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 클라이언트 IP 추출 유틸. ALB 등 프록시 뒤에서도 원 IP 를 얻기 위해 사용한다.
 */
public final class ClientIpResolver {

    /** IP 를 판별할 수 없을 때 사용하는 값. */
    public static final String UNKNOWN = "unknown";

    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    private ClientIpResolver() {
        // 유틸 클래스
    }

    /**
     * {@code X-Forwarded-For} 의 첫 IP → {@code X-Real-IP} → {@code remoteAddr} 순으로 해석한다.
     *
     * @param request 현재 요청(null 허용)
     * @return 클라이언트 IP. 판별 불가 시 {@value #UNKNOWN}
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }

        String forwardedFor = request.getHeader(HEADER_X_FORWARDED_FOR);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            for (String candidate : forwardedFor.split(",")) {
                String ip = candidate.trim();
                if (isUsable(ip)) {
                    return ip;
                }
            }
        }

        String realIp = request.getHeader(HEADER_X_REAL_IP);
        if (realIp != null && isUsable(realIp.trim())) {
            return realIp.trim();
        }

        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr == null || remoteAddr.isBlank()) ? UNKNOWN : remoteAddr.trim();
    }

    private static boolean isUsable(String ip) {
        return !ip.isEmpty() && !UNKNOWN.equalsIgnoreCase(ip);
    }
}
