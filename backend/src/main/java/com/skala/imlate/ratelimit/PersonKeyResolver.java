package com.skala.imlate.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.imlate.common.properties.ImlateProperties;
import com.skala.imlate.registration.service.RegistrationService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 등록 요청 본문에서 <b>개인 단위 rate limit 키</b>를 만든다(SPEC §8.3).
 *
 * <pre>
 * personKey  = normalize(반) + "|" + normalize(이름) + "|" + normalize(호수)
 * personHash = hex(HMAC-SHA256(lookup.token-secret, personKey)) 앞 32자
 * bucketKey  = "imlate:rl:register:person:" + personHash
 * </pre>
 *
 * <p><b>정규화는 {@link RegistrationService#normalize(String)} 를 그대로 재사용한다.</b>
 * 서비스와 리미터가 다른 규칙을 쓰면 {@code "1반"} 과 {@code " 1반 "} 이 다른 사람이 되어
 * 공백만 바꿔 가며 제한을 우회할 수 있다. (ratelimit → registration.service 단방향 참조이고
 * registration 쪽은 ratelimit 을 참조하지 않으므로 순환 의존은 없다. 정적 메서드라 빈 의존도 생기지 않는다.)
 *
 * <p><b>왜 원문을 그대로 키에 넣지 않는가(개인정보)</b> — Redis 키는 {@code SCAN} 결과, 슬로우로그,
 * 모니터링 대시보드, RDB/AOF 덤프, 운영자의 {@code redis-cli} 화면에 그대로 드러난다.
 * 원문을 박으면 교육생 실명과 기숙사 호수가 인프라 계층에 평문으로 남는다.
 * 버킷은 <b>세기만</b> 할 뿐 원문을 되읽을 일이 없으므로 되돌릴 수 있을 필요가 없다.
 *
 * <p><b>왜 단순 SHA-256 이 아니라 HMAC 인가</b> — 입력 공간이 작다(반 몇 개 × 이름 × 호수).
 * 평문 해시는 사전 공격으로 되돌릴 수 있어 "해시했으니 안전"이 성립하지 않는다.
 * 이미 존재하는 비밀값({@code imlate.lookup.token-secret})을 키로 쓰면 덤프를 손에 넣어도 원문을
 * 복원할 수 없다(새 시크릿을 추가하지 않는다).
 * 시크릿이 비어 있는 환경(기본 설정 그대로인 개발 환경)에서는 SHA-256 으로 내려가되,
 * 그 사실을 기동 시 한 번 경고로 남긴다 — 운영 프로파일은 시크릿이 필수라 항상 HMAC 이다.
 */
@Component
public class PersonKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(PersonKeyResolver.class);

    /** 해시 문자열에서 사용할 앞자리 수(16진수 32자 = 128비트). */
    private static final int HASH_PREFIX_LENGTH = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final String FIELD_CLASS_NAME = "className";
    private static final String FIELD_STUDENT_NAME = "studentName";
    private static final String FIELD_ROOM_NUMBER = "roomNumber";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final ObjectMapper objectMapper;

    /** HMAC 키. 시크릿이 비어 있으면 null 이고, 그때만 SHA-256 으로 내려간다. */
    private final SecretKeySpec hmacKey;

    /**
     * @param objectMapper 본문 파싱용(스프링이 구성한 것을 그대로 쓴다)
     * @param properties   {@code imlate.lookup.token-secret} 을 HMAC 키로 사용한다
     */
    public PersonKeyResolver(ObjectMapper objectMapper, ImlateProperties properties) {
        this.objectMapper = objectMapper;
        String secret = properties.lookup().tokenSecret();
        if (secret == null || secret.isBlank()) {
            this.hmacKey = null;
            log.warn("imlate.lookup.token-secret 이 비어 있어 개인 rate limit 키를 SHA-256 으로 만든다. "
                    + "운영에서는 시크릿을 반드시 설정해 HMAC 을 사용할 것(해시 역추적 방지).");
        } else {
            this.hmacKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        }
    }

    /**
     * 요청에 캐시된 본문에서 개인 버킷 식별자를 만든다.
     *
     * <p>본문이 없거나(캐싱 대상이 아니거나) 형식이 이상하면 <b>null</b> 을 돌려준다.
     * 그 경우 개인 버킷 검사는 건너뛴다 — 리미터가 정상 등록을 막는 것보다 낫고,
     * 어차피 잘못된 본문은 컨트롤러의 {@code @Valid} 에서 400 으로 걸린다.
     *
     * @param request 현재 요청(null 허용)
     * @return 개인 키 해시 앞 {@value #HASH_PREFIX_LENGTH} 자. 만들 수 없으면 null
     */
    public String resolve(HttpServletRequest request) {
        byte[] body = CachedBodyHttpServletRequest.cachedBody(request);
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            String className = normalizedText(root, FIELD_CLASS_NAME);
            String studentName = normalizedText(root, FIELD_STUDENT_NAME);
            String roomNumber = normalizedText(root, FIELD_ROOM_NUMBER);
            if (className.isEmpty() || studentName.isEmpty() || roomNumber.isEmpty()) {
                return null;
            }
            // 구분자('|')가 없으면 "1반"+"1김교육" 과 "1반1"+"김교육" 이 같은 키가 된다.
            return hash(className + '|' + studentName + '|' + roomNumber);
        } catch (Exception e) {
            // 본문 파싱 실패는 정상 시나리오(깨진 JSON)다. 값 자체가 개인정보이므로 내용은 로그에 남기지 않는다.
            log.debug("등록 본문에서 개인 키를 만들지 못했다 — 개인 rate limit 을 건너뛴다. cause={}", e.toString());
            return null;
        }
    }

    /** JSON 필드를 꺼내 서비스와 동일한 규칙으로 정규화한다(문자열이 아니면 빈 값). */
    private static String normalizedText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            return "";
        }
        return RegistrationService.normalize(node.asText());
    }

    /**
     * personKey 를 해시해 앞 {@value #HASH_PREFIX_LENGTH} 자(16진수)를 만든다.
     *
     * <p>{@link Mac}·{@link MessageDigest} 는 스레드 안전하지 않아 호출마다 새로 얻는다.
     * 40바이트 남짓 입력의 HMAC-SHA256 은 마이크로초 단위이고, 바로 뒤에 Redis 왕복이 따라오므로
     * 측정 가능한 비용이 아니다(R14).
     */
    private String hash(String personKey) throws Exception {
        byte[] input = personKey.getBytes(StandardCharsets.UTF_8);
        byte[] digest;
        if (hmacKey != null) {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            digest = mac.doFinal(input);
        } else {
            digest = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(input);
        }
        char[] out = new char[HASH_PREFIX_LENGTH];
        for (int i = 0; i < HASH_PREFIX_LENGTH / 2; i++) {
            int b = digest[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }
}
