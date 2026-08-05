package com.skala.imlate.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.imlate.support.TestFixtures;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@link PersonKeyResolver} 단위 테스트.
 *
 * <p>개인 버킷의 키가 여기서 나온다. 정규화 규칙이 {@code RegistrationService} 와 어긋나면
 * {@code "1반"} 과 {@code " 1반 "} 이 다른 사람이 되어 제한을 우회할 수 있으므로 그 부분을 집중해서 본다.
 */
@DisplayName("개인 키 추출(PersonKeyResolver)")
class PersonKeyResolverTest {

    private PersonKeyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PersonKeyResolver(new ObjectMapper(), TestFixtures.imlateProperties());
    }

    /** 본문이 캐시된 등록 요청을 만든다(운영과 동일하게 필터를 통과시킨다). */
    private static HttpServletRequest requestWithBody(String json) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/registrations");
        request.setContentType("application/json");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return new CachedBodyHttpServletRequest(request, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String body(String className, String studentName, String roomNumber) {
        return "{\"className\":\"" + className + "\",\"studentName\":\"" + studentName
                + "\",\"roomNumber\":\"" + roomNumber + "\"}";
    }

    @Test
    @DisplayName("반·이름·호수로 32자 해시 키를 만든다(SPEC §8.3)")
    void 개인_키를_만든다() {
        String key = resolver.resolve(requestWithBody(body("SKALA1", "김교육", "301")));

        assertThat(key).isNotNull().hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("시크릿이 다르면 같은 사람이라도 다른 해시가 나온다(HMAC 이라 사전 공격으로 되돌릴 수 없다)")
    void 시크릿이_다르면_해시도_다르다() {
        PersonKeyResolver other = new PersonKeyResolver(new ObjectMapper(),
                TestFixtures.imlatePropertiesWithSecret("완전히-다른-시크릿-0123456789"));

        assertThat(other.resolve(requestWithBody(body("SKALA1", "김교육", "301"))))
                .isNotEqualTo(resolver.resolve(requestWithBody(body("SKALA1", "김교육", "301"))));
    }

    @Test
    @DisplayName("시크릿이 비어 있으면 SHA-256 으로 내려가되 키 생성은 계속된다(기동 실패 금지)")
    void 시크릿이_없어도_동작한다() {
        PersonKeyResolver noSecret = new PersonKeyResolver(new ObjectMapper(),
                TestFixtures.imlatePropertiesWithSecret(""));

        assertThat(noSecret.resolve(requestWithBody(body("SKALA1", "김교육", "301"))))
                .isNotNull().hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("개인정보(이름·호수)가 키에 평문으로 들어가지 않는다")
    void 개인정보가_평문으로_노출되지_않는다() {
        String key = resolver.resolve(requestWithBody(body("SKALA1", "김교육", "301")));

        assertThat(key).doesNotContain("김교육").doesNotContain("301").doesNotContain("SKALA1");
    }

    @Test
    @DisplayName("앞뒤 공백·연속 공백만 다른 입력은 같은 키가 된다(서비스 정규화와 동일 규칙)")
    void 정규화_규칙이_서비스와_같다() {
        String key = resolver.resolve(requestWithBody(body("SKALA1", "김 교육", "301")));

        assertThat(resolver.resolve(requestWithBody(body(" SKALA1 ", "김  교육", " 301 ")))).isEqualTo(key);
        // JSON 이스케이프된 탭(\t)도 공백 한 칸으로 줄어든다.
        assertThat(resolver.resolve(requestWithBody(body("SKALA1", "김\\t교육", "301")))).isEqualTo(key);
    }

    @Test
    @DisplayName("사람이 다르면 키도 다르다(필드 하나만 달라도 구분된다)")
    void 사람이_다르면_키도_다르다() {
        String base = resolver.resolve(requestWithBody(body("SKALA1", "김교육", "301")));

        assertThat(resolver.resolve(requestWithBody(body("SKALA2", "김교육", "301")))).isNotEqualTo(base);
        assertThat(resolver.resolve(requestWithBody(body("SKALA1", "이교육", "301")))).isNotEqualTo(base);
        assertThat(resolver.resolve(requestWithBody(body("SKALA1", "김교육", "302")))).isNotEqualTo(base);
    }

    @Test
    @DisplayName("필드가 다른 경계에 걸쳐도 다른 사람으로 구분한다(구분자 주입 방지)")
    void 구분자를_섞어도_다른_사람이다() {
        // 구분자가 없으면 "SKALA1|김교육" 과 "SKALA|1김교육" 이 같은 키가 되어 버린다.
        assertThat(resolver.resolve(requestWithBody(body("SKALA1", "김교육", "301"))))
                .isNotEqualTo(resolver.resolve(requestWithBody(body("SKALA", "1김교육", "301"))));
    }

    @Test
    @DisplayName("본문이 없거나 캐시되지 않았으면 null 을 돌려준다(개인 버킷을 건너뛴다)")
    void 본문이_없으면_null() {
        MockHttpServletRequest plain = new MockHttpServletRequest("POST", "/api/v1/registrations");

        assertThat(resolver.resolve(plain)).isNull();
        assertThat(resolver.resolve(null)).isNull();
        assertThat(resolver.resolve(requestWithBody(""))).isNull();
    }

    @Test
    @DisplayName("JSON 이 깨졌거나 필드가 비면 null 을 돌려준다(리미터가 정상 등록을 막지 않게)")
    void 잘못된_본문이면_null() {
        assertThat(resolver.resolve(requestWithBody("{ 깨진 JSON"))).isNull();
        assertThat(resolver.resolve(requestWithBody("[1,2,3]"))).isNull();
        assertThat(resolver.resolve(requestWithBody(body("SKALA1", "", "301")))).isNull();
        assertThat(resolver.resolve(requestWithBody(body("SKALA1", "   ", "301")))).isNull();
        assertThat(resolver.resolve(requestWithBody("{\"className\":\"SKALA1\"}"))).isNull();
        assertThat(resolver.resolve(requestWithBody("{\"className\":1,\"studentName\":\"김\",\"roomNumber\":\"3\"}")))
                .isNull();
    }
}
