package com.skala.imlate.common.properties;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 사감 발송(문자·이메일) 설정(`imlate.notification.*`).
 *
 * @param enabled        발송 기능 사용 여부(false 면 스케줄러가 아무것도 하지 않는다)
 * @param dispatchCron   정기 발송 cron(기본 "0 10 22 * * *")
 * @param retryCron      실패 재시도 cron(기본 "0 25,40 22 * * *")
 * @param maxAttempts    채널별 최대 시도 횟수
 * @param lockTtlSeconds 중복 발송 방지용 Redis 락 TTL(초)
 * @param supervisors    수신 사감 목록
 */
@ConfigurationProperties(prefix = "imlate.notification")
public record NotificationProperties(
        boolean enabled,
        String dispatchCron,
        String retryCron,
        int maxAttempts,
        long lockTtlSeconds,
        List<Supervisor> supervisors
) {

    public NotificationProperties {
        if (dispatchCron == null || dispatchCron.isBlank()) {
            dispatchCron = "0 10 22 * * *";
        }
        if (retryCron == null || retryCron.isBlank()) {
            retryCron = "0 25,40 22 * * *";
        }
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }
        if (lockTtlSeconds <= 0) {
            lockTtlSeconds = 300L;
        }
        // 불변 리스트로 고정한다. 미설정이면 빈 목록.
        supervisors = supervisors == null ? List.of() : List.copyOf(supervisors);
    }

    /**
     * 수신 사감 1명.
     *
     * @param name  표시 이름
     * @param phone 휴대폰 번호(비어 있으면 SMS SKIP)
     * @param email 이메일 주소(비어 있으면 EMAIL SKIP)
     */
    public record Supervisor(String name, String phone, String email) {

        public Supervisor {
            if (name == null) {
                name = "";
            }
            if (phone == null) {
                phone = "";
            }
            if (email == null) {
                email = "";
            }
        }
    }
}
