package com.skala.imlate.notification.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.properties.NotificationProperties;

/**
 * 멀티 인스턴스 중복 발송 방지용 분산 락.
 *
 * <p>{@code SET imlate:lock:dispatch:{date} <uuid> NX EX {lockTtlSeconds}} 로 획득하고,
 * 해제는 Lua 로 <b>내가 넣은 값일 때만</b> 삭제한다(다른 인스턴스가 새로 잡은 락을 지우지 않기 위함).
 *
 * <p>Redis 를 쓸 수 없으면 {@code tryAcquire} 가 true 를 반환해 락 없이 진행한다.
 * 이 경우에도 {@code notification_dispatch} 테이블의 SUCCESS 이력으로 중복 발송을 한 번 더 막으므로 안전하다.
 */
@Component
public class DispatchLockManager {

    private static final Logger log = LoggerFactory.getLogger(DispatchLockManager.class);

    private static final String KEY_PREFIX = "imlate:lock:dispatch:";
    /** 값이 일치할 때만 삭제하는 해제 스크립트. */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final NotificationProperties properties;

    public DispatchLockManager(StringRedisTemplate redisTemplate, NotificationProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /** 락 키. */
    public String key(LocalDate date) {
        return KEY_PREFIX + date;
    }

    /**
     * 락 획득을 시도한다.
     *
     * @return 진행해도 되면 true. 다른 인스턴스가 이미 잡고 있으면 false.
     *         Redis 장애 시에는 true(락 없이 진행 — DB 이력으로 중복 방지)
     */
    public boolean tryAcquire(LocalDate date, String token) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key(date), token, Duration.ofSeconds(properties.lockTtlSeconds()));
            if (Boolean.TRUE.equals(acquired)) {
                log.debug("발송 락 획득: key={}", key(date));
                return true;
            }
            return false;
        } catch (Exception ex) {
            // Redis 불가 → 락 없이 진행한다. 중복 발송은 DB 이력(SUCCESS 존재 여부)으로 막히므로 안전하다.
            log.warn("발송 락을 사용할 수 없어 락 없이 진행합니다(DB 이력으로 중복 방지). date={}, cause={}",
                    date, ex.toString());
            return true;
        }
    }

    /** 내가 잡은 락이면 해제한다. 실패해도 TTL 로 자동 만료되므로 예외를 전파하지 않는다. */
    public void release(LocalDate date, String token) {
        try {
            Long removed = redisTemplate.execute(RELEASE_SCRIPT, List.of(key(date)), token);
            if (removed == null || removed == 0L) {
                log.debug("해제할 발송 락이 없습니다(이미 만료되었거나 다른 소유자). key={}", key(date));
            }
        } catch (Exception ex) {
            log.warn("발송 락 해제 실패(TTL 로 자동 만료됩니다). date={}, cause={}", date, ex.toString());
        }
    }
}
