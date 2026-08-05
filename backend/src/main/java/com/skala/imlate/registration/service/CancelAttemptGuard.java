package com.skala.imlate.registration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.ImlateProperties;

/**
 * 취소 비밀번호 대입 시도를 제한하는 가드.
 *
 * <p><b>왜 rate limiter 로 부족한가</b> — 개인 축 rate limit 은 분당 10회 같은 <u>속도</u> 제한이다.
 * 그런데 비밀번호는 4자리, 경우의 수가 1만 개뿐이다. 분당 10회면 하루에 1만 4천 번을 시도할 수 있어
 * <b>느긋하게 두드리면 반드시 뚫린다</b>. 속도가 아니라 <u>총량</u>을 막아야 한다.
 *
 * <p>그래서 사람·날짜 단위로 실패 횟수를 세고, 상한({@code max-attempts}, 기본 10회)을 넘으면
 * 그날은 더 이상 시도할 수 없게 한다. 10 / 10000 = 0.1% — 찍어서 맞출 확률이 충분히 낮다.
 * 본인이 비밀번호를 잊었다면 10번은 충분히 넉넉하고, 그 뒤에는 운영진 문의로 안내한다.
 *
 * <p><b>이 제한이 만드는 부작용(의도한 것)</b> — 남의 등록에 일부러 10번 틀려서 그 사람이
 * 취소하지 못하게 만들 수 있다. 그 경우 피해자는 <u>명단에 남는다</u>. 즉 최악의 결과가
 * "취소하려던 사람이 그대로 명단에 있다"이고, 이는 문이 잠겨 밖에서 밤을 새는 것보다 훨씬 가볍다.
 * 반대로 상한을 두지 않으면 남의 등록을 지워 <b>실제로 밖에 갇히게</b> 만들 수 있다.
 * 두 위험을 견주어 가벼운 쪽을 택했다.
 *
 * <p><b>Redis 장애 시에는 취소를 거부한다(fail-closed).</b> 등록 경로가 Redis 장애를 무시하고
 * 진행하는 것과 정반대인데, 실패했을 때 벌어지는 일이 다르기 때문이다.
 * 등록이 막히면 교육생이 명단에서 빠져 <b>문 밖에 갇힌다</b>(치명적).
 * 취소가 막히면 명단에 그대로 남을 뿐이다(무해 — 안 들어가면 그만이다).
 * 시도 횟수를 셀 수 없는 상태에서 취소를 열어 두면 그 시간이 곧 무제한 대입 창이 된다.
 */
@Component
public class CancelAttemptGuard {

    private static final Logger log = LoggerFactory.getLogger(CancelAttemptGuard.class);

    /** Redis 키 접두사. 뒤에 {@code {일자}:{개인키해시}} 가 붙는다. */
    private static final String KEY_PREFIX = "imlate:cancel:fail:";

    /** 자정을 넘겨 키가 남지 않도록 하는 여유(대사·정리 작업과 겹치지 않게 조금 넉넉히 둔다). */
    private static final Duration TTL_MARGIN = Duration.ofHours(1);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** 개인키 해시 유도 라벨. 다른 용도(조회 토큰·rate limit 키)와 값이 겹치지 않게 한다. */
    private static final String KEY_LABEL = "imlate:cancel-attempt:v1|";

    /** 키에 쓸 해시 앞자리 수(16진수 32자 = 128비트). 충돌 걱정 없이 키 길이를 묶어 둔다. */
    private static final int HASH_PREFIX_LENGTH = 32;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final StringRedisTemplate redisTemplate;
    private final int maxAttempts;

    /** 개인키 해시용 HMAC 키. 시크릿이 비어 있으면 null 이고 그때만 SHA-256 으로 내려간다. */
    private final SecretKeySpec hmacKey;

    /**
     * @param redisTemplate Redis 접근
     * @param properties    {@code imlate.registration.cancel.max-attempts} 와
     *                      개인키 해시에 쓸 {@code imlate.lookup.token-secret}
     */
    public CancelAttemptGuard(StringRedisTemplate redisTemplate, ImlateProperties properties) {
        this.redisTemplate = redisTemplate;
        this.maxAttempts = properties.registration().cancel().maxAttempts();
        String secret = properties.lookup().tokenSecret();
        // PersonKeyResolver 와 같은 방침 — 새 시크릿을 만들지 않고 기존 시크릿을 용도 분리해 쓴다.
        this.hmacKey = (secret == null || secret.isBlank())
                ? null
                : new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /**
     * 사람 식별 키를 만든다. Redis 키에 이름·호수가 평문으로 남지 않게 해시한다.
     *
     * @param className   반
     * @param studentName 이름
     * @param roomNumber  기숙사 호수
     * @return 해시 앞 {@value #HASH_PREFIX_LENGTH} 자
     */
    public String personKey(String className, String studentName, String roomNumber) {
        // 구분자가 없으면 "1반"+"1김교육" 과 "1반1"+"김교육" 이 같은 키가 된다.
        String material = KEY_LABEL + className + '|' + studentName + '|' + roomNumber;
        byte[] raw = material.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest;
            if (hmacKey == null) {
                digest = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(raw);
            } else {
                Mac mac = Mac.getInstance(HMAC_ALGORITHM);
                mac.init(hmacKey);
                digest = mac.doFinal(raw);
            }
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return sb.substring(0, HASH_PREFIX_LENGTH);
        } catch (Exception ex) {
            throw new IllegalStateException("취소 시도 키를 만들 수 없습니다.", ex);
        }
    }

    /** 하루 최대 실패 허용 횟수(안내 문구에 쓴다). */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * 지금 시도해도 되는지 확인한다.
     *
     * @param date      대상일
     * @param personKey 개인 식별 키(호출자가 해시해서 넘긴다)
     * @return 허용되면 true. 상한 초과이거나 <b>Redis 를 읽을 수 없으면</b> false
     */
    public boolean allowAttempt(LocalDate date, String personKey) {
        try {
            String value = redisTemplate.opsForValue().get(key(date, personKey));
            return value == null || parse(value) < maxAttempts;
        } catch (RuntimeException ex) {
            // fail-closed. 클래스 Javadoc 의 근거 참고.
            log.warn("취소 시도 횟수를 확인할 수 없어 취소를 거부한다(Redis 장애). date={} cause={}", date, ex.toString());
            return false;
        }
    }

    /**
     * 실패 1회를 기록한다.
     *
     * @param date      대상일
     * @param personKey 개인 식별 키(해시)
     * @param now       현재 시각(TTL 계산용)
     * @return 이번 실패까지 누적된 횟수. 셀 수 없었으면 {@code -1}
     */
    public long recordFailure(LocalDate date, String personKey, LocalDateTime now) {
        String key = key(date, personKey);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            // 첫 실패에서만 만료를 건다. 매번 걸면 계속 틀리는 동안 만료가 밀려 영구 잠금이 된다.
            if (count != null && count == 1L) {
                redisTemplate.expire(key, ttlUntilEndOfDay(now));
            }
            long value = count == null ? -1L : count;
            if (value >= maxAttempts) {
                // 개인키는 해시라 이름·호수가 로그에 남지 않는다.
                log.warn("취소 비밀번호 실패 상한 도달 — 오늘은 더 시도할 수 없다. date={} person={} count={}",
                        date, personKey, value);
            }
            return value;
        } catch (RuntimeException ex) {
            log.warn("취소 실패 횟수를 기록하지 못했다. date={} cause={}", date, ex.toString());
            return -1L;
        }
    }

    /**
     * 성공했으므로 실패 누적을 지운다.
     *
     * <p>본인이 여러 번 틀린 끝에 맞췄다면 그 기록을 들고 있을 이유가 없다.
     * 실패하더라도 조용히 넘어간다 — 취소는 이미 성공했다.
     *
     * @param date      대상일
     * @param personKey 개인 식별 키(해시)
     */
    public void reset(LocalDate date, String personKey) {
        try {
            redisTemplate.delete(key(date, personKey));
        } catch (RuntimeException ex) {
            log.debug("취소 실패 횟수 초기화 실패(무시). date={} cause={}", date, ex.toString());
        }
    }

    private static String key(LocalDate date, String personKey) {
        return KEY_PREFIX + date + ':' + personKey;
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            // 값이 깨졌다면 셀 수 없는 것과 같다. 안전한 쪽(상한 도달)으로 해석한다.
            return Long.MAX_VALUE;
        }
    }

    /** 자정까지 남은 시간 + 여유. 날짜가 바뀌면 시도 횟수도 새로 시작한다. */
    private static Duration ttlUntilEndOfDay(LocalDateTime now) {
        Duration untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay());
        if (untilMidnight.isNegative() || untilMidnight.isZero()) {
            untilMidnight = Duration.ofMinutes(1);
        }
        return untilMidnight.plus(TTL_MARGIN);
    }
}
