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
 * @param opsAlert       발송 실패 시 운영자에게 보내는 알림 설정(사감 수신처와 <b>분리</b>)
 * @param heartbeat      발송 완료 하트비트(외부 감시용) 설정
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
        List<Supervisor> supervisors,
        OpsAlert opsAlert,
        Heartbeat heartbeat
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
        // 운영자 알림 수신 메일은 미지정 시 문의처 메일을 그대로 쓴다(위 contactEmail 기본값 처리 이후여야 한다).
        opsAlert = OpsAlert.normalize(opsAlert, contactEmail);
        heartbeat = heartbeat == null ? Heartbeat.defaults() : heartbeat;
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
        this(enabled, dispatchCron, retryCron, maxAttempts, lockTtlSeconds, supervisors, null);
    }

    /**
     * 운영자 알림 설정만 지정하는 축약 생성자(주로 테스트).
     *
     * @param enabled        발송 기능 사용 여부
     * @param dispatchCron   정기 발송 cron
     * @param retryCron      실패 재시도 cron
     * @param maxAttempts    채널별 최대 시도 횟수
     * @param lockTtlSeconds 중복 발송 방지용 Redis 락 TTL(초)
     * @param supervisors    수신 사감 목록
     * @param opsAlert       운영자 알림 설정(null 이면 기본값)
     */
    public NotificationProperties(boolean enabled, String dispatchCron, String retryCron,
                                  int maxAttempts, long lockTtlSeconds,
                                  List<Supervisor> supervisors, OpsAlert opsAlert) {
        this(enabled, dispatchCron, retryCron, maxAttempts, lockTtlSeconds, null, null,
                supervisors, opsAlert, null);
    }

    /**
     * 문의처만 지정하는 축약 생성자(주로 테스트).
     *
     * @param enabled        발송 기능 사용 여부
     * @param dispatchCron   정기 발송 cron
     * @param retryCron      실패 재시도 cron
     * @param maxAttempts    채널별 최대 시도 횟수
     * @param lockTtlSeconds 중복 발송 방지용 Redis 락 TTL(초)
     * @param contactName    문의처 이름
     * @param contactEmail   문의처 이메일
     * @param supervisors    수신 사감 목록
     */
    public NotificationProperties(boolean enabled, String dispatchCron, String retryCron,
                                  int maxAttempts, long lockTtlSeconds,
                                  String contactName, String contactEmail,
                                  List<Supervisor> supervisors) {
        this(enabled, dispatchCron, retryCron, maxAttempts, lockTtlSeconds,
                contactName, contactEmail, supervisors, null, null);
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

    /**
     * 운영자 알림 설정(`imlate.notification.ops-alert.*`).
     *
     * <p><b>사감 수신처와 반드시 분리한다.</b> 사감에게 가는 것은 "오늘 밤 복귀 명단"뿐이고,
     * 발송 실패·잔액 부족 같은 운영 이슈는 운영자만 받아야 한다. 사감에게 운영 알림이 가면
     * 사감이 명단 문자와 혼동해 실제 명단을 놓칠 수 있다.
     *
     * <p>{@code phone} 기본값이 빈 값인 이유: 잘못된 번호가 들어가면 그 문자는 사감에게 간다.
     * "설정하지 않으면 문자 알림 없음"이 안전한 기본값이다(메일만으로도 운영자는 인지한다).
     *
     * @param enabled         운영자 알림 사용 여부(미설정이면 true)
     * @param email           운영자 알림 수신 메일(비우면 {@code contact-email} 과 동일)
     * @param phone           운영자 알림 수신 번호(<b>비우면 문자 알림을 하지 않는다 — 기본값</b>)
     * @param notifyOnSuccess 전부 성공했을 때도 알릴지 여부(기본 false — 매일 오는 알림은 무시하게 된다)
     */
    public record OpsAlert(Boolean enabled, String email, String phone, boolean notifyOnSuccess) {

        public OpsAlert {
            // 미설정(null)과 명시적 false 를 구분해야 해서 원시형 boolean 이 아니라 Boolean 을 쓴다.
            enabled = enabled == null || enabled;
            email = email == null ? "" : email.trim();
            phone = phone == null ? "" : phone.trim();
        }

        /** 설정이 통째로 비어 있을 때/메일이 비었을 때 문의처 메일로 채운 값을 만든다. */
        static OpsAlert normalize(OpsAlert source, String fallbackEmail) {
            OpsAlert base = source == null ? new OpsAlert(null, null, null, false) : source;
            if (!base.email().isBlank()) {
                return base;
            }
            return new OpsAlert(base.enabled(), fallbackEmail, base.phone(), base.notifyOnSuccess());
        }

        /** 문자 알림을 보낼 수 있는지(번호가 설정되어 있는지). */
        public boolean hasPhone() {
            return !phone.isBlank();
        }
    }

    /**
     * 발송 완료 하트비트 설정(`imlate.notification.heartbeat.*`).
     *
     * <p>21:50 에 <b>아무 일도 일어나지 않은 경우</b>(인스턴스 다운 등)를 밖에서 감지하기 위한 신호다.
     * 앱은 "발송을 끝냈다"는 사실만 남기고, 그 신호가 오지 않는 것을 CloudWatch 알람이 판정한다.
     *
     * @param enabled     하트비트 사용 여부(미설정이면 true. 로컬/테스트에서는 false 로 끈다)
     * @param namespace   CloudWatch 네임스페이스(기본 "Imlate")
     * @param environment 지표 차원 {@code Environment} 값(기본 "local")
     */
    public record Heartbeat(Boolean enabled, String publisher, String namespace, String environment) {

        /**
         * 발행기 선택. {@code log} | {@code cloudwatch} | {@code none}.
         *
         * <p>구현이 셋이라 {@code enabled} 하나로는 배타적으로 갈리지 않는다
         * ({@code @ConditionalOnProperty} 는 서로 다른 두 프로퍼티의 AND 를 표현하지 못한다).
         * 그래서 선택은 이 값 하나로만 한다.
         */
        public static final String DEFAULT_PUBLISHER = "log";

        /** CloudWatch 네임스페이스 기본값. */
        public static final String DEFAULT_NAMESPACE = "Imlate";

        /** {@code Environment} 차원 기본값. */
        public static final String DEFAULT_ENVIRONMENT = "local";

        public Heartbeat {
            enabled = enabled == null || enabled;
            if (publisher == null || publisher.isBlank()) {
                publisher = DEFAULT_PUBLISHER;
            }
            if (namespace == null || namespace.isBlank()) {
                namespace = DEFAULT_NAMESPACE;
            }
            if (environment == null || environment.isBlank()) {
                environment = DEFAULT_ENVIRONMENT;
            }
        }

        static Heartbeat defaults() {
            return new Heartbeat(null, null, null, null);
        }
    }
}
