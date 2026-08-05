package com.skala.imlate.registration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.ImlateProperties;

/**
 * 취소 비밀번호(숫자 4자리) 해시·검증기.
 *
 * <p><b>4자리는 근본적으로 약하다.</b> 경우의 수가 1만 개뿐이라 일반적인 해시(SHA-256 등)로 저장하면
 * DB 덤프를 손에 넣은 사람이 1초 안에 전부 되돌릴 수 있다. 그래서 두 겹으로 막는다.
 *
 * <ol>
 *   <li><b>PBKDF2 (느린 해시)</b> — 반복 횟수만큼 계산을 강제해 대입 1회의 비용을 올린다.</li>
 *   <li><b>pepper (서버 시크릿)</b> — DB 에 없는 값을 해시 입력에 섞는다.
 *       DB만 유출되면 pepper 를 모르므로 <u>대입 자체가 불가능</u>하다.
 *       (salt 는 행마다 다르지만 DB 안에 있어서 유출 시 같이 새어 나간다 — pepper 가 그 빈틈을 메운다.)</li>
 * </ol>
 *
 * <p><b>왜 새 시크릿을 만들지 않는가</b> — pepper 를 새 환경변수로 두면 SSM 파라미터가 하나 늘고,
 * 그러면 코드 배포만으로 끝나지 않고 {@code terraform apply} 까지 필요해진다.
 * 대신 이미 있는 {@code imlate.lookup.token-secret} 에서 <b>용도 분리(domain separation)</b> 를 거쳐 유도한다:
 * {@code pepper = HMAC-SHA256(시크릿, "imlate:cancel-pin:pepper:v1")}.
 * HMAC 은 단방향이라 pepper 가 새어도 원래 시크릿을 역산할 수 없고, 라벨이 다르므로
 * 조회 토큰 서명({@code LookupTokenService})이나 개인 rate limit 키({@code PersonKeyResolver})와
 * 값이 절대 겹치지 않는다. 같은 시크릿을 쓰되 <b>서로 다른 키</b>가 되는 것이다.
 * ({@code PersonKeyResolver} 가 이미 같은 시크릿을 재사용하는 선례를 따른다 — "새 시크릿을 추가하지 않는다".)
 *
 * <p>저장 형식은 자기 서술적이라 나중에 반복 횟수를 올려도 기존 행을 그대로 검증할 수 있다:
 * <pre>pbkdf2-sha256$&lt;반복횟수&gt;$&lt;base64(salt)&gt;$&lt;base64(hash)&gt;</pre>
 */
@Component
public class CancelPasswordHasher {

    private static final Logger log = LoggerFactory.getLogger(CancelPasswordHasher.class);

    /** 저장 형식의 알고리즘 식별자. 형식이 바뀌면 이 값도 바뀐다. */
    private static final String ALGORITHM_ID = "pbkdf2-sha256";

    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * pepper 유도 라벨. <b>절대 바꾸지 말 것</b> — 바꾸면 기존에 저장된 모든 해시가 검증에 실패한다.
     * 버전 접미사({@code v1})는 훗날 의도적으로 회전해야 할 때를 위한 자리다.
     */
    private static final String PEPPER_LABEL = "imlate:cancel-pin:pepper:v1";

    /** 파생 키 길이(비트). SHA-256 출력과 맞춘다. */
    private static final int KEY_LENGTH_BITS = 256;

    /** salt 길이(바이트). 행마다 새로 뽑는다. */
    private static final int SALT_LENGTH_BYTES = 16;

    /** 시크릿이 비어 있는 개발 환경에서 쓰는 대체 pepper 원문. 운영에서는 절대 쓰이지 않는다. */
    private static final String DEV_FALLBACK_SECRET = "imlate-local-dev-only";

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getDecoder();

    /** 유도된 pepper 를 16진수 문자열로 들고 있는다(해시 입력에 문자로 섞기 위해). */
    private final String pepper;

    /**
     * 새 해시를 만들 때 쓸 반복 횟수.
     *
     * <p><b>검증에는 쓰지 않는다.</b> 검증은 저장된 문자열에 적힌 값을 그대로 따르므로,
     * 이 설정을 올려도 이미 저장된 해시는 계속 검증된다.
     */
    private final int iterations;

    /**
     * @param properties pepper 는 {@code imlate.lookup.token-secret} 에서 유도하고,
     *                   반복 횟수는 {@code imlate.registration.cancel.hash-iterations} 를 쓴다
     */
    public CancelPasswordHasher(ImlateProperties properties) {
        this.iterations = properties.registration().cancel().hashIterations();
        String secret = properties.lookup().tokenSecret();
        if (secret == null || secret.isBlank()) {
            // PersonKeyResolver 와 동일한 방침: 개발 환경은 뜨게 하되 사실을 경고로 남긴다.
            // 운영 프로파일은 시크릿이 필수라 이 경로를 타지 않는다.
            log.warn("imlate.lookup.token-secret 이 비어 있어 취소 비밀번호 pepper 를 개발용 고정값으로 만든다. "
                    + "운영에서는 시크릿을 반드시 설정할 것(DB 유출 시 4자리 비밀번호가 대입으로 뚫린다).");
            secret = DEV_FALLBACK_SECRET;
        }
        this.pepper = derivePepper(secret);
    }

    /**
     * 평문 4자리를 저장 가능한 해시 문자열로 만든다.
     *
     * @param rawPassword 숫자 4자리(호출자가 이미 형식을 검증한 상태여야 한다)
     * @return {@code pbkdf2-sha256$반복$salt$hash} 형식의 문자열
     * @throws IllegalArgumentException 비밀번호가 비어 있을 때
     */
    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("취소 비밀번호가 비어 있습니다.");
        }
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        random.nextBytes(salt);
        byte[] derived = pbkdf2(rawPassword, salt, iterations);
        return ALGORITHM_ID + '$' + iterations + '$' + encoder.encodeToString(salt) + '$'
                + encoder.encodeToString(derived);
    }

    /**
     * 평문 4자리가 저장된 해시와 일치하는지 검사한다.
     *
     * <p>비교는 {@link MessageDigest#isEqual(byte[], byte[])} 로 <b>상수 시간</b>에 한다.
     * 바이트 단위로 조기 종료하는 비교를 쓰면 응답 시간 차이로 앞자리부터 한 자리씩 맞춰 나갈 수 있다.
     *
     * <p>저장된 해시가 {@code null}(V2 이전에 등록된 행)이거나 형식이 깨졌으면 조용히 false 다 —
     * 예외를 던지면 "이 행은 비밀번호가 없다"는 사실이 응답 차이로 드러난다.
     *
     * @param rawPassword 입력받은 평문(null 허용)
     * @param stored      저장된 해시 문자열(null 허용)
     * @return 일치하면 true
     */
    public boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || rawPassword.isEmpty() || stored == null || stored.isBlank()) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !ALGORITHM_ID.equals(parts[0])) {
            log.warn("취소 비밀번호 해시 형식을 알 수 없다 — 검증 실패로 처리한다. prefix={}", parts.length > 0 ? parts[0] : "");
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = decoder.decode(parts[2]);
            byte[] expected = decoder.decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException ex) {
            log.warn("취소 비밀번호 해시를 해석하지 못했다 — 검증 실패로 처리한다. cause={}", ex.toString());
            return false;
        }
    }

    /**
     * PBKDF2 파생.
     *
     * <p>비밀번호 문자열에 pepper 를 이어 붙여 입력으로 쓴다. 구분자 {@code '|'} 는 숫자에 나올 수 없으므로
     * 4자리와 pepper 의 경계가 모호해지지 않는다.
     */
    private byte[] pbkdf2(String rawPassword, byte[] salt, int iterations) {
        char[] material = (rawPassword + '|' + pepper).toCharArray();
        PBEKeySpec spec = new PBEKeySpec(material, salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            // PBKDF2WithHmacSHA256 은 JDK 표준이라 정상 환경에서는 발생하지 않는다.
            throw new IllegalStateException("취소 비밀번호 해시를 계산할 수 없습니다.", ex);
        } finally {
            // 파생이 끝나면 평문+pepper 가 담긴 배열을 지운다(힙 덤프에 남는 시간을 줄인다).
            Arrays.fill(material, '\0');
            spec.clearPassword();
        }
    }

    /** {@code HMAC-SHA256(시크릿, 라벨)} 을 16진수 문자열로. 시크릿과 용도가 다른 독립 키가 된다. */
    private static String derivePepper(String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] out = mac.doFinal(PEPPER_LABEL.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("취소 비밀번호 pepper 를 유도할 수 없습니다.", ex);
        }
    }
}
