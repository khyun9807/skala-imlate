package com.skala.imlate.common.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 클라이언트 IP 추출 유틸.
 *
 * <p><b>메서드가 두 개인 이유(보안)</b>
 * <ul>
 *   <li>{@link #resolve(HttpServletRequest)} — 프록시 헤더를 <b>무조건</b> 신뢰한다.
 *       감사 로그·통계처럼 "틀려도 서비스 판단에 영향이 없는" 용도 전용이다.</li>
 *   <li>{@link #resolve(HttpServletRequest, TrustedProxies)} — {@code remoteAddr} 가 신뢰 프록시일 때만
 *       프록시 헤더를 채택한다. <b>rate limit 처럼 헤더 위조가 곧 우회가 되는 곳</b>은 반드시 이쪽을 쓴다.</li>
 * </ul>
 *
 * <p>왜 필요한가 — {@code X-Forwarded-For} 는 누구나 붙일 수 있는 평범한 요청 헤더다.
 * 무조건 신뢰하면 공격자가 요청마다 다른 값을 넣어 IP 버킷을 <u>무한히</u> 만들어 내고,
 * IP 기준 rate limit 은 사실상 없는 것이 된다.
 */
public final class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    /** IP 를 판별할 수 없을 때 사용하는 값. */
    public static final String UNKNOWN = "unknown";

    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    /** IPv6 루프백의 확장 표기. 컨테이너/톰캣 설정에 따라 {@code ::1} 대신 이 값이 올 수 있다. */
    private static final String IPV6_LOOPBACK_LONG = "0:0:0:0:0:0:0:1";
    private static final String IPV6_LOOPBACK = "::1";
    /** IPv4-mapped IPv6 접두어({@code ::ffff:127.0.0.1}). */
    private static final String IPV4_MAPPED_PREFIX = "::ffff:";

    private ClientIpResolver() {
        // 유틸 클래스
    }

    /**
     * {@code X-Forwarded-For} 의 첫 IP → {@code X-Real-IP} → {@code remoteAddr} 순으로 해석한다.
     *
     * <p><b>주의</b> — 이 메서드는 프록시 헤더의 진위를 검사하지 않는다(호출부 호환을 위해 기존 동작을 그대로 둔다).
     * 값이 위조되어도 무방한 <b>표시·감사·통계</b> 용도로만 쓰고,
     * 보안 판단(rate limit·차단 등)에는 {@link #resolve(HttpServletRequest, TrustedProxies)} 를 써야 한다.
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

    /**
     * 신뢰 프록시 판별을 거쳐 클라이언트 IP 를 해석한다(보안 판단용).
     *
     * <p>판정 순서
     * <ol>
     *   <li>{@code trustedProxies} 가 비어 있으면 → 프록시 헤더를 <b>전혀 보지 않고</b> {@code remoteAddr} 를 쓴다.
     *       (프록시 없이 직접 노출된 배포에서 헤더 위조로 리미터를 우회하는 것을 원천 차단한다)</li>
     *   <li>{@code remoteAddr} 가 신뢰 목록에 없으면 → 우리 프록시를 거쳐 온 요청이 아니므로 {@code remoteAddr} 를 쓴다.</li>
     *   <li>신뢰 프록시에서 온 요청이면 → {@code X-Forwarded-For} 를 <b>오른쪽에서 왼쪽으로</b> 훑어
     *       신뢰 목록에 없는 첫 항목을 클라이언트로 본다. 없으면 {@code X-Real-IP} → {@code remoteAddr}.</li>
     * </ol>
     *
     * <p><b>왜 "첫 IP" 가 아니라 "오른쪽에서 첫 미신뢰 IP" 인가</b> —
     * 우리 nginx 는 {@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for} 로
     * 클라이언트가 보낸 헤더 <u>뒤에 이어 붙인다</u>(ALB 도 동일하다). 그래서 공격자가
     * {@code X-Forwarded-For: 1.2.3.4} 를 넣으면 최종 헤더는 {@code "1.2.3.4, 진짜IP"} 가 되고,
     * <b>맨 앞 값은 공격자가 마음대로 정할 수 있다</b>. 반대로 맨 뒤는 우리 프록시가 직접 써넣은 값이라
     * 위조할 수 없다. 프록시가 헤더를 통째로 덮어쓰는 구성이라면 두 방식의 결과는 같다.
     *
     * @param request        현재 요청(null 허용)
     * @param trustedProxies 신뢰 프록시 집합(null 허용 — null 이면 아무 프록시도 신뢰하지 않는다)
     * @return 클라이언트 IP. 판별 불가 시 {@value #UNKNOWN}
     */
    public static String resolve(HttpServletRequest request, TrustedProxies trustedProxies) {
        if (request == null) {
            return UNKNOWN;
        }

        String remoteAddr = normalize(request.getRemoteAddr());
        String peer = isUsable(remoteAddr) ? remoteAddr : UNKNOWN;

        if (trustedProxies == null || trustedProxies.isEmpty() || !trustedProxies.contains(peer)) {
            return peer;
        }

        String forwarded = clientFromForwardedFor(request.getHeader(HEADER_X_FORWARDED_FOR), trustedProxies);
        if (forwarded != null) {
            return forwarded;
        }

        // nginx 는 X-Real-IP 를 $remote_addr 로 "덮어쓰므로" 신뢰 프록시 뒤에서는 위조될 수 없다.
        String realIp = normalize(request.getHeader(HEADER_X_REAL_IP));
        if (isUsable(realIp)) {
            return realIp;
        }
        return peer;
    }

    /**
     * {@code X-Forwarded-For} 목록에서 실제 클라이언트를 고른다(오른쪽 → 왼쪽, 신뢰 프록시는 건너뛴다).
     *
     * @return 클라이언트 IP. 쓸 만한 값이 없으면 null
     */
    private static String clientFromForwardedFor(String headerValue, TrustedProxies trustedProxies) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String[] hops = headerValue.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String ip = normalize(hops[i]);
            if (!isUsable(ip) || trustedProxies.contains(ip)) {
                continue;
            }
            return ip;
        }
        return null;
    }

    private static boolean isUsable(String ip) {
        return ip != null && !ip.isEmpty() && !UNKNOWN.equalsIgnoreCase(ip);
    }

    /**
     * 비교 가능한 형태로 IP 를 정규화한다.
     *
     * <p>포트({@code 1.2.3.4:5678}), 대괄호({@code [2001:db8::1]}), 존 ID({@code fe80::1%eth0})를 떼고
     * IPv4-mapped IPv6({@code ::ffff:127.0.0.1})는 IPv4 로 되돌린다. IPv6 루프백 표기도 하나로 맞춘다.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String ip = raw.trim();
        if (ip.isEmpty()) {
            return "";
        }

        if (ip.charAt(0) == '[') {
            int end = ip.indexOf(']');
            ip = end > 1 ? ip.substring(1, end) : ip.substring(1);
        } else {
            int colon = ip.indexOf(':');
            // 콜론이 하나뿐이면 "IPv4:포트" 다. 둘 이상이면 IPv6 주소이므로 건드리지 않는다.
            if (colon > 0 && ip.indexOf(':', colon + 1) < 0) {
                ip = ip.substring(0, colon);
            }
        }

        int zone = ip.indexOf('%');
        if (zone > 0) {
            ip = ip.substring(0, zone);
        }

        ip = ip.toLowerCase(Locale.ROOT);
        if (ip.startsWith(IPV4_MAPPED_PREFIX) && parseIpv4(ip.substring(IPV4_MAPPED_PREFIX.length())) != null) {
            ip = ip.substring(IPV4_MAPPED_PREFIX.length());
        }
        if (IPV6_LOOPBACK_LONG.equals(ip)) {
            ip = IPV6_LOOPBACK;
        }
        return ip;
    }

    /**
     * 점 4개 표기의 IPv4 를 32비트 정수로 바꾼다.
     *
     * @return 32비트 주소. IPv4 형식이 아니면 null
     */
    static Integer parseIpv4(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int result = 0;
        int octets = 0;
        int value = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                value = (value < 0 ? 0 : value) * 10 + (c - '0');
                if (value > 255) {
                    return null;
                }
            } else if (c == '.') {
                if (value < 0 || ++octets > 3) {
                    return null;
                }
                result = (result << 8) | value;
                value = -1;
            } else {
                return null;
            }
        }
        if (value < 0 || octets != 3) {
            return null;
        }
        return (result << 8) | value;
    }

    /**
     * 신뢰 프록시 집합. 설정 문자열을 <b>기동 시 한 번만</b> 파싱해 두고,
     * 요청 시에는 정수 비교(IPv4) 또는 해시 조회(IPv6)만 한다 — 요청당 추가 비용이 사실상 없다(R14).
     *
     * <p>표기법
     * <ul>
     *   <li>IPv4 CIDR: {@code 10.0.0.0/16}, {@code 127.0.0.1/32}</li>
     *   <li>IPv4 단일: {@code 127.0.0.1} (= {@code /32})</li>
     *   <li>IPv6: 정확 일치만 지원한다({@code ::1}). 프록시는 대개 고정 주소라 실무상 충분하다.</li>
     * </ul>
     */
    public static final class TrustedProxies {

        private static final TrustedProxies NONE = new TrustedProxies(List.of(), Set.of());

        private final List<Cidr> ipv4Ranges;
        private final Set<String> exactMatches;

        private TrustedProxies(List<Cidr> ipv4Ranges, Set<String> exactMatches) {
            this.ipv4Ranges = ipv4Ranges;
            this.exactMatches = exactMatches;
        }

        /** 아무 프록시도 신뢰하지 않는 집합. */
        public static TrustedProxies none() {
            return NONE;
        }

        /**
         * 설정값 목록을 파싱한다. 형식이 잘못된 항목은 WARN 로그를 남기고 <b>무시</b>한다
         * (설정 오타 하나로 애플리케이션이 못 뜨는 것보다, 그 항목만 신뢰하지 않는 쪽이 안전하다).
         *
         * @param patterns {@code imlate.rate-limit.trusted-proxies} 값(null 허용)
         * @return 신뢰 프록시 집합
         */
        public static TrustedProxies of(List<String> patterns) {
            if (patterns == null || patterns.isEmpty()) {
                return NONE;
            }
            List<Cidr> ranges = new ArrayList<>(patterns.size());
            Set<String> exact = new LinkedHashSet<>();
            for (String pattern : patterns) {
                if (pattern == null || pattern.isBlank()) {
                    continue;
                }
                String entry = pattern.trim();
                int slash = entry.indexOf('/');
                if (slash < 0) {
                    Integer address = parseIpv4(normalize(entry));
                    if (address != null) {
                        ranges.add(new Cidr(address, -1));
                    } else {
                        exact.add(normalize(entry));
                    }
                    continue;
                }
                Cidr cidr = parseCidr(entry, slash);
                if (cidr == null) {
                    log.warn("신뢰 프록시 설정을 해석할 수 없어 무시한다: '{}'", entry);
                } else {
                    if (cidr.mask() == 0) {
                        log.warn("신뢰 프록시에 전체 대역('{}')이 지정되었다 — 모든 X-Forwarded-For 를 "
                                + "신뢰하게 되므로 IP rate limit 이 무력화될 수 있다.", entry);
                    }
                    ranges.add(cidr);
                }
            }
            if (ranges.isEmpty() && exact.isEmpty()) {
                return NONE;
            }
            return new TrustedProxies(List.copyOf(ranges), Set.copyOf(exact));
        }

        private static Cidr parseCidr(String entry, int slash) {
            Integer address = parseIpv4(normalize(entry.substring(0, slash)));
            if (address == null) {
                return null;
            }
            int prefix;
            try {
                prefix = Integer.parseInt(entry.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (prefix < 0 || prefix > 32) {
                return null;
            }
            // prefix 가 0 이면 -1 << 32 가 정의되지 않으므로(자바는 시프트 값을 32로 나눈 나머지로 처리) 따로 계산한다.
            int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
            return new Cidr(address & mask, mask);
        }

        /** 신뢰할 프록시가 하나도 없으면 true. */
        public boolean isEmpty() {
            return ipv4Ranges.isEmpty() && exactMatches.isEmpty();
        }

        /** 설정된 항목 수(기동 로그용). */
        public int size() {
            return ipv4Ranges.size() + exactMatches.size();
        }

        /**
         * 주어진 IP 가 신뢰 프록시인지 판정한다.
         *
         * @param ip 판정할 IP(정규화 전 값도 허용)
         * @return 신뢰 대역에 속하면 true
         */
        public boolean contains(String ip) {
            if (ip == null || ip.isEmpty() || isEmpty()) {
                return false;
            }
            String normalized = normalize(ip);
            Integer address = parseIpv4(normalized);
            if (address != null) {
                for (Cidr range : ipv4Ranges) {
                    if (range.contains(address)) {
                        return true;
                    }
                }
                return false;
            }
            return exactMatches.contains(normalized);
        }

        /** IPv4 CIDR 한 칸. {@code mask} 는 상위 prefix 비트가 1 인 비트마스크다. */
        private record Cidr(int network, int mask) {

            boolean contains(int address) {
                return (address & mask) == network;
            }
        }
    }
}
