package com.skala.imlate.common.properties;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 사감 발송(문자·이메일) 설정(`imlate.notification.*`).
 *
 * <p>발송 시각 근거(하루 타임라인): <b>21:45 등록 마감 → 5분 뒤 21:50 사감 발송 →
 * 22:05 / 22:20 실패분 재시도 → 22:30 통금(문 잠김) → 23:30 일괄 개방</b>.
 * 재시도는 두 번 모두 통금(22:30) 전에 끝나야 사감이 문을 잠그기 전에 명단을 확인할 수 있다.
 *
 * @param enabled        발송 기능 사용 여부(false 면 스케줄러가 아무것도 하지 않는다)
 * @param dispatchCron   정기 발송 cron(기본 "0 50 21 * * *" = 21:50)
 * @param retryCron      실패 재시도 cron(기본 "0 5,20 22 * * *" = 22:05, 22:20)
 * @param maxAttempts    채널별 최대 시도 횟수
 * @param lockTtlSeconds 중복 발송 방지용 Redis 락 TTL(초)
 * @param contactName    안내 문구에 노출할 문의처 이름(기본 "SKALA 운영진")
 * @param contactEmail   안내 문구에 노출할 문의처 이메일(기본 "khdev07@naver.com")
 * @param supervisors    수신 사감 목록
 */
@ConfigurationProperties(prefix = "imlate.notification")
public record NotificationProperties(
        boolean enabled,
        String dispatchCron,
        String retryCron,
        int maxAttempts,
        long lockTtlSeconds,
        String contactName,
        String contactEmail,
        List<Supervisor> supervisors
) {

    /**
     * 정기 발송 기본 cron — 21:50.
     *
     * <p>등록 마감(21:45) 5분 뒤. 마감 직전 등록분이 DB 에 안착하고 WAL↔DB 대사가 끝날 여유다.
     */
    public static final String DEFAULT_DISPATCH_CRON = "0 50 21 * * *";

    /**
     * 실패 재시도 기본 cron — 22:05, 22:20.
     *
     * <p>발송(21:50) +15분 / +30분. 6필드(초 분 시 일 월 요일) 문법이며, 두 시각이 같은 "시(22)"에
     * 있어야 하나의 cron 식으로 표현할 수 있다. 예컨대 21:55 와 22:05 를 한 식에 담으려고
     * {@code "0 55,5 21,22 * * *"} 라고 쓰면 21:05·21:55·22:05·22:55 네 번 실행되어(발송 전 실행 포함)
     * 의도와 달라진다. 그래서 재시도는 22:05 / 22:20 으로 둔다 — 두 번 모두 통금(22:30) 이전이다.
     */
    public static final String DEFAULT_RETRY_CRON = "0 5,20 22 * * *";

    /** 문의처 이름 기본값. 운영진이 바뀌면 설정으로 교체한다(코드 수정 불필요). */
    public static final String DEFAULT_CONTACT_NAME = "SKALA 운영진";

    /** 문의처 이메일 기본값. 운영진이 바뀌면 설정으로 교체한다(코드 수정 불필요). */
    public static final String DEFAULT_CONTACT_EMAIL = "khdev07@naver.com";

    @ConstructorBinding
    public NotificationProperties {
        if (dispatchCron == null || dispatchCron.isBlank()) {
            dispatchCron = DEFAULT_DISPATCH_CRON;
        }
        if (retryCron == null || retryCron.isBlank()) {
            retryCron = DEFAULT_RETRY_CRON;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }
        if (lockTtlSeconds <= 0) {
            lockTtlSeconds = 300L;
        }
        if (contactName == null || contactName.isBlank()) {
            contactName = DEFAULT_CONTACT_NAME;
        }
        if (contactEmail == null || contactEmail.isBlank()) {
            contactEmail = DEFAULT_CONTACT_EMAIL;
        }
        // 불변 리스트로 고정한다. 미설정이면 빈 목록.
        supervisors = supervisors == null ? List.of() : List.copyOf(supervisors);
    }

    /**
     * 문의처를 기본값으로 두는 축약 생성자.
     *
     * <p>문의처 문구를 바꿀 일이 없는 호출부(주로 테스트)가 매번 null 두 개를 넘기지 않도록 둔다.
     * 설정 바인딩은 {@link ConstructorBinding} 이 붙은 정규 생성자만 사용한다.
     *
     * @param enabled        발송 기능 사용 여부
     * @param dispatchCron   정기 발송 cron
     * @param retryCron      실패 재시도 cron
     * @param maxAttempts    채널별 최대 시도 횟수
     * @param lockTtlSeconds 중복 발송 방지용 Redis 락 TTL(초)
     * @param supervisors    수신 사감 목록
     */
    public NotificationProperties(boolean enabled, String dispatchCron, String retryCron,
                                  int maxAttempts, long lockTtlSeconds,
                                  List<Supervisor> supervisors) {
        this(enabled, dispatchCron, retryCron, maxAttempts, lockTtlSeconds, null, null, supervisors);
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
