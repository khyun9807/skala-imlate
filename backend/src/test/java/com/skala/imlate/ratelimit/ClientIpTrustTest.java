package com.skala.imlate.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.skala.imlate.common.web.ClientIpResolver;
import com.skala.imlate.common.web.ClientIpResolver.TrustedProxies;

/**
 * 신뢰 프록시 판별({@link ClientIpResolver#resolve(jakarta.servlet.http.HttpServletRequest, TrustedProxies)})
 * 테스트.
 *
 * <p>rate limit 의 버킷 키가 곧 이 값이므로, 여기가 뚫리면 <b>IP 기준 제한 자체가 무의미해진다</b>.
 * (공격자가 요청마다 다른 {@code X-Forwarded-For} 를 넣어 버킷을 무한히 만들 수 있다)
 */
@DisplayName("클라이언트 IP 판별 · 신뢰 프록시(ClientIpResolver)")
class ClientIpTrustTest {

    private static final String PROXY_IP = "127.0.0.1";
    private static final String REAL_CLIENT_IP = "203.0.113.7";

    private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/registrations");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Nested
    @DisplayName("X-Forwarded-For 신뢰 판별")
    class ForwardedFor {

        @Test
        @DisplayName("신뢰 목록이 비어 있으면 XFF 를 무시하고 remoteAddr 를 쓴다")
        void 신뢰_목록이_비면_XFF_를_무시한다() {
            MockHttpServletRequest request = request("198.51.100.9", "1.2.3.4");

            assertThat(ClientIpResolver.resolve(request, TrustedProxies.none())).isEqualTo("198.51.100.9");
            assertThat(ClientIpResolver.resolve(request, TrustedProxies.of(List.of()))).isEqualTo("198.51.100.9");
            assertThat(ClientIpResolver.resolve(request, null)).isEqualTo("198.51.100.9");
        }

        @Test
        @DisplayName("신뢰 목록에 없는 곳에서 온 요청이면 XFF 를 무시한다")
        void 미신뢰_출처면_XFF_를_무시한다() {
            TrustedProxies trusted = TrustedProxies.of(List.of(PROXY_IP));
            MockHttpServletRequest request = request("198.51.100.9", "1.2.3.4");

            assertThat(ClientIpResolver.resolve(request, trusted)).isEqualTo("198.51.100.9");
        }

        @Test
        @DisplayName("신뢰 프록시에서 온 요청이면 XFF 를 채택한다")
        void 신뢰_프록시면_XFF_를_채택한다() {
            TrustedProxies trusted = TrustedProxies.of(List.of(PROXY_IP));

            assertThat(ClientIpResolver.resolve(request(PROXY_IP, REAL_CLIENT_IP), trusted))
                    .isEqualTo(REAL_CLIENT_IP);
        }

        @Test
        @DisplayName("nginx 가 헤더를 이어 붙여도(위조 값 + 진짜 IP) 진짜 IP 를 골라낸다")
        void 위조된_앞부분을_무시하고_진짜_IP_를_고른다() {
            // nginx 의 $proxy_add_x_forwarded_for 는 클라이언트가 보낸 값 "뒤에" 진짜 IP 를 붙인다.
            // 따라서 맨 앞은 공격자가 마음대로 정할 수 있고, 맨 뒤만 위조할 수 없다.
            TrustedProxies trusted = TrustedProxies.of(List.of(PROXY_IP));
            MockHttpServletRequest request = request(PROXY_IP, "1.2.3.4, 5.6.7.8, " + REAL_CLIENT_IP);

            assertThat(ClientIpResolver.resolve(request, trusted)).isEqualTo(REAL_CLIENT_IP);
        }

        @Test
        @DisplayName("XFF 마지막 항목이 신뢰 프록시면 그 앞의 미신뢰 IP 를 클라이언트로 본다(프록시 2단)")
        void 프록시가_여러_단이면_미신뢰_첫_IP_를_고른다() {
            TrustedProxies trusted = TrustedProxies.of(List.of("127.0.0.1/32", "10.0.0.0/8"));
            MockHttpServletRequest request = request(PROXY_IP, REAL_CLIENT_IP + ", 10.0.1.5");

            assertThat(ClientIpResolver.resolve(request, trusted)).isEqualTo(REAL_CLIENT_IP);
        }

        @Test
        @DisplayName("신뢰 프록시 뒤에 XFF 가 없으면 X-Real-IP → remoteAddr 순으로 본다")
        void XFF_가_없으면_X_Real_IP_를_본다() {
            TrustedProxies trusted = TrustedProxies.of(List.of(PROXY_IP));
            MockHttpServletRequest withRealIp = request(PROXY_IP, null);
            withRealIp.addHeader("X-Real-IP", REAL_CLIENT_IP);

            assertThat(ClientIpResolver.resolve(withRealIp, trusted)).isEqualTo(REAL_CLIENT_IP);
            assertThat(ClientIpResolver.resolve(request(PROXY_IP, null), trusted)).isEqualTo(PROXY_IP);
        }

        @Test
        @DisplayName("IP 를 전혀 판별할 수 없으면 unknown 을 쓴다(우회 통로를 만들지 않는다)")
        void 판별_불가면_unknown() {
            MockHttpServletRequest request = request("", null);

            assertThat(ClientIpResolver.resolve(request, TrustedProxies.none()))
                    .isEqualTo(ClientIpResolver.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("CIDR 매칭")
    class Cidr {

        @Test
        @DisplayName("/32 는 정확히 그 IP 하나만 포함한다")
        void 슬래시32() {
            TrustedProxies trusted = TrustedProxies.of(List.of("127.0.0.1/32"));

            assertThat(trusted.contains("127.0.0.1")).isTrue();
            assertThat(trusted.contains("127.0.0.2")).isFalse();
            assertThat(trusted.contains("127.0.1.1")).isFalse();
        }

        @Test
        @DisplayName("/24 는 마지막 옥텟만 다른 주소를 포함하고 범위 밖은 제외한다")
        void 슬래시24() {
            TrustedProxies trusted = TrustedProxies.of(List.of("10.0.5.0/24"));

            assertThat(trusted.contains("10.0.5.0")).isTrue();
            assertThat(trusted.contains("10.0.5.1")).isTrue();
            assertThat(trusted.contains("10.0.5.255")).isTrue();
            assertThat(trusted.contains("10.0.4.255")).isFalse();
            assertThat(trusted.contains("10.0.6.0")).isFalse();
        }

        @Test
        @DisplayName("/16 경계값(0.0 ~ 255.255)과 인접 대역을 정확히 가른다")
        void 슬래시16_경계값() {
            TrustedProxies trusted = TrustedProxies.of(List.of("172.16.0.0/16"));

            assertThat(trusted.contains("172.16.0.0")).isTrue();
            assertThat(trusted.contains("172.16.255.255")).isTrue();
            assertThat(trusted.contains("172.15.255.255")).isFalse();
            assertThat(trusted.contains("172.17.0.0")).isFalse();
        }

        @Test
        @DisplayName("/0 은 모든 IPv4 를 포함한다(설정 실수 방지용 경고 대상)")
        void 슬래시0() {
            TrustedProxies trusted = TrustedProxies.of(List.of("0.0.0.0/0"));

            assertThat(trusted.contains("1.2.3.4")).isTrue();
            assertThat(trusted.contains("255.255.255.255")).isTrue();
        }

        @Test
        @DisplayName("프리픽스 없는 단일 IP 는 /32 로 다룬다")
        void 단일_IP는_슬래시32() {
            TrustedProxies trusted = TrustedProxies.of(List.of("192.168.0.10"));

            assertThat(trusted.contains("192.168.0.10")).isTrue();
            assertThat(trusted.contains("192.168.0.11")).isFalse();
        }

        @Test
        @DisplayName("IPv6 는 정확 일치로 판정하고 표기 차이(::1 / 0:0:...:1)를 흡수한다")
        void IPv6_정확_일치() {
            TrustedProxies trusted = TrustedProxies.of(List.of("::1"));

            assertThat(trusted.contains("::1")).isTrue();
            assertThat(trusted.contains("0:0:0:0:0:0:0:1")).isTrue();
            assertThat(trusted.contains("2001:db8::1")).isFalse();
        }

        @Test
        @DisplayName("포트·대괄호·IPv4-mapped 표기가 섞여도 같은 주소로 인식한다")
        void 표기_정규화() {
            TrustedProxies trusted = TrustedProxies.of(List.of("127.0.0.1/32", "::1"));

            assertThat(trusted.contains("127.0.0.1:54321")).isTrue();
            assertThat(trusted.contains("::ffff:127.0.0.1")).isTrue();
            assertThat(trusted.contains("[::1]")).isTrue();
        }

        @Test
        @DisplayName("형식이 잘못된 항목은 무시하고 나머지만 신뢰한다(설정 오타로 기동이 막히지 않게)")
        void 잘못된_설정은_무시한다() {
            TrustedProxies trusted = TrustedProxies.of(
                    Arrays.asList("127.0.0.1/32", "10.0.0.0/33", "not-an-ip/24", "1.2.3.4/x", null, "  "));

            assertThat(trusted.contains("127.0.0.1")).isTrue();
            assertThat(trusted.contains("10.0.0.1")).isFalse();
            assertThat(trusted.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("빈 목록은 '아무도 신뢰하지 않음' 이다")
        void 빈_목록() {
            assertThat(TrustedProxies.of(List.of()).isEmpty()).isTrue();
            assertThat(TrustedProxies.of(null).isEmpty()).isTrue();
            assertThat(TrustedProxies.none().contains("127.0.0.1")).isFalse();
        }
    }
}
