package com.skala.imlate.registration.wal;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.imlate.common.properties.ImlateProperties;

/**
 * Redis 기반 등록 WAL 저장소(R7).
 *
 * <p>키 구조: {@code imlate:wal:{yyyy-MM-dd}} (HASH, field = walId, value = {@link WalEntry} JSON),
 * TTL = {@code imlate.wal.ttl-days}.
 *
 * <p><b>예외 정책</b> — 호출자가 가용성 판단을 할 수 있도록 메서드별로 다르게 처리한다.
 * <ul>
 *   <li>{@link #append(WalEntry)} : 실패 시 예외를 <b>전파</b>한다. 등록 서비스가 이를 잡아
 *       "WAL 미기록" 으로 표시하고 등록 자체는 계속 진행한다(가용성 우선).</li>
 *   <li>{@link #updateStatus(String, LocalDate, WalStatus)} : best-effort. 실패해도 예외를 던지지 않는다.</li>
 *   <li>조회 메서드 : 예외를 전파한다. 대사 서비스가 잡아 {@code WAL_UNAVAILABLE} 로 보고한다.</li>
 *   <li>{@link #isAvailable()} : 예외를 삼키고 {@code false} 를 반환한다.</li>
 * </ul>
 */
@Repository
public class RegistrationWalRepository {

    private static final Logger log = LoggerFactory.getLogger(RegistrationWalRepository.class);

    private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ttl;

    /**
     * @param redisTemplate 문자열 Redis 템플릿(foundation 제공)
     * @param objectMapper  WAL JSON 전용 매퍼({@code imlateObjectMapper})
     * @param properties    {@code imlate.wal.*} 설정
     */
    public RegistrationWalRepository(StringRedisTemplate redisTemplate,
                                     @Qualifier("imlateObjectMapper") ObjectMapper objectMapper,
                                     ImlateProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefix = properties.wal().keyPrefix();
        this.ttl = Duration.ofDays(properties.wal().ttlDays());
    }

    /**
     * WAL 항목을 추가하고 TTL 을 갱신한다(HSET + EXPIRE).
     *
     * @param entry 기록할 항목
     * @throws org.springframework.dao.DataAccessException Redis 장애 시
     */
    public void append(WalEntry entry) {
        String key = key(entry.registrationDate());
        hash().put(key, entry.walId(), toJson(entry));
        // TTL 은 매 기록마다 재설정한다(키가 TTL 없이 남는 상황을 막는다).
        redisTemplate.expire(key, ttl);
        log.debug("WAL appended key={} walId={} status={}", key, entry.walId(), entry.status());
    }

    /**
     * WAL 항목의 상태를 갱신한다. 항목이 없으면 아무것도 하지 않는다.
     *
     * <p>best-effort — 실패해도 예외를 던지지 않는다(등록 응답을 막지 않기 위함).
     *
     * @param walId  WAL 항목 식별자
     * @param date   복귀 대상일
     * @param status 새 상태
     */
    public void updateStatus(String walId, LocalDate date, WalStatus status) {
        try {
            String key = key(date);
            String raw = hash().get(key, walId);
            if (raw == null) {
                log.debug("WAL entry not found for status update key={} walId={}", key, walId);
                return;
            }
            WalEntry updated = objectMapper.readValue(raw, WalEntry.class).withStatus(status);
            hash().put(key, walId, toJson(updated));
            log.debug("WAL status updated key={} walId={} status={}", key, walId, status);
        } catch (JsonProcessingException ex) {
            log.warn("WAL status update failed (JSON) walId={} date={} cause={}", walId, date, ex.toString());
        } catch (RuntimeException ex) {
            log.warn("WAL status update failed (Redis) walId={} date={} cause={}", walId, date, ex.toString());
        }
    }

    /**
     * 해당 일자의 WAL 항목을 모두 읽는다. 깨진 JSON 항목은 경고 후 건너뛴다.
     *
     * @param date 복귀 대상일
     * @return WAL 항목 목록(없으면 빈 목록)
     * @throws org.springframework.dao.DataAccessException Redis 장애 시
     */
    public List<WalEntry> findAllByDate(LocalDate date) {
        String key = key(date);
        Map<String, String> raw = hash().entries(key);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<WalEntry> entries = new ArrayList<>(raw.size());
        for (Map.Entry<String, String> field : raw.entrySet()) {
            try {
                entries.add(objectMapper.readValue(field.getValue(), WalEntry.class));
            } catch (JsonProcessingException ex) {
                // 한 건이 깨져도 전체 대사를 막지 않는다.
                log.warn("WAL entry deserialization failed key={} walId={} cause={}", key, field.getKey(), ex.toString());
            }
        }
        return entries;
    }

    /**
     * 단일 WAL 항목을 조회한다.
     *
     * @param date  복귀 대상일
     * @param walId WAL 항목 식별자
     * @return 항목(없거나 깨졌으면 empty)
     * @throws org.springframework.dao.DataAccessException Redis 장애 시
     */
    public Optional<WalEntry> find(LocalDate date, String walId) {
        String raw = hash().get(key(date), walId);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, WalEntry.class));
        } catch (JsonProcessingException ex) {
            log.warn("WAL entry deserialization failed date={} walId={} cause={}", date, walId, ex.toString());
            return Optional.empty();
        }
    }

    /**
     * 해당 일자의 WAL 항목 수(HASH 필드 개수).
     *
     * @param date 복귀 대상일
     * @return 항목 수
     * @throws org.springframework.dao.DataAccessException Redis 장애 시
     */
    public long countByDate(LocalDate date) {
        Long size = hash().size(key(date));
        return size == null ? 0L : size;
    }

    /**
     * Redis 사용 가능 여부(PING).
     *
     * @return 응답이 오면 true, 장애면 false
     */
    public boolean isAvailable() {
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            return pong != null;
        } catch (RuntimeException ex) {
            log.warn("Redis WAL unavailable: {}", ex.toString());
            return false;
        }
    }

    /**
     * 해당 일자의 WAL 키({@code imlate:wal:2026-08-05}).
     *
     * @param date 복귀 대상일
     * @return Redis 키
     */
    public String key(LocalDate date) {
        return keyPrefix + ":" + KEY_DATE_FORMAT.format(date);
    }

    private HashOperations<String, String, String> hash() {
        return redisTemplate.opsForHash();
    }

    private String toJson(WalEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException ex) {
            // 직렬화 실패는 코드 결함이므로 그대로 올린다(서비스가 잡아 WAL 미기록으로 처리).
            throw new IllegalStateException("WAL 항목을 JSON 으로 변환할 수 없습니다: walId=" + entry.walId(), ex);
        }
    }
}
