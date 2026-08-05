package com.skala.imlate.common.properties;

import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * imlate 서비스 전역 설정(`imlate.*`). 등록 창 시각·WAL·조회 토큰·관리자 키를 담는다.
 *
 * @param timezone     서비스 기준 시간대(기본 "Asia/Seoul")
 * @param registration 등록 창/안내 시각 및 입력 길이 제한
 * @param wal          Redis WAL 설정
 * @param lookup       사감 조회 페이지/토큰 설정
 * @param admin        관리 API 키 설정
 */
@ConfigurationProperties(prefix = "imlate")
public record ImlateProperties(
        String timezone,
        Registration registration,
        Wal wal,
        Lookup lookup,
        Admin admin
) {

    /** 기본 시간대. 설정이 비어 있을 때 사용한다. */
    public static final String DEFAULT_TIMEZONE = "Asia/Seoul";

    public ImlateProperties {
        // 시간대가 비어 있으면 기본값(Asia/Seoul)으로 채운다.
        if (timezone == null || timezone.isBlank()) {
            timezone = DEFAULT_TIMEZONE;
        }
        if (registration == null) {
            registration = new Registration(null, null, null, null, 0, 0);
        }
        if (wal == null) {
            wal = new Wal(null, 0);
        }
        if (lookup == null) {
            lookup = new Lookup(null, null, 0);
        }
        if (admin == null) {
            admin = new Admin(null);
        }
    }

    /**
     * 등록 창 정책.
     *
     * <p>하루 타임라인: <b>00:00 등록 시작 → 21:45 등록 마감 → 21:50 사감 발송 →
     * 22:30 통금(문 잠김) → 23:30 일괄 개방</b>. 마감을 21:45 로 앞당긴 이유는
     * 발송(21:50) 전에 명단을 확정할 5분을 확보하고, 사감이 통금(22:30) 전에
     * 명단을 확인할 여유를 두기 위해서다. 21:45 이후에는 그날 등록이 닫히고,
     * 자정(00:00)에 다음 날 대상 등록이 열린다.
     *
     * @param openTime      등록 시작 시각(포함, 기본 00:00)
     * @param closeTime     등록 마감 시각(미포함, 기본 21:45)
     * @param returnTime    연장 복귀 시각(기본 23:30) — 안내 문구용
     * @param curfewTime    원래 통금 시각(기본 22:30) — 안내 문구용
     * @param maxNameLength 이름 최대 길이
     * @param maxRoomLength 호수 최대 길이
     * @param cancel        등록 취소 정책
     */
    public record Registration(LocalTime openTime, LocalTime closeTime,
                               LocalTime returnTime, LocalTime curfewTime,
                               int maxNameLength, int maxRoomLength,
                               Cancel cancel) {

        /**
         * 취소 정책 없이 만드는 축약 생성자(주로 테스트).
         *
         * <p><b>이 생성자가 생기면서 아래 정규 생성자에 {@link ConstructorBinding} 이 필수가 되었다.</b>
         * 스프링은 생성자가 둘 이상이면 어느 것으로 바인딩할지 스스로 판단하지 않는다.
         * 표시가 없으면 바인딩이 조용히 실패해 <u>application.yml 과 환경변수를 전부 무시하고</u>
         * 레코드 기본값만 쓰게 된다. 기본값이 설정값과 우연히 같으면 아무 문제 없어 보이지만,
         * {@code IMLATE_REGISTRATION_CLOSE_TIME} 같은 운영 스위치가 죽은 채로 배포된다.
         * (실제로 로컬 통합 시험에서 마감 시각을 23:59 로 덮어써도 21:45 가 나와 발견했다)
         *
         * @param openTime      등록 시작 시각
         * @param closeTime     등록 마감 시각
         * @param returnTime    연장 복귀 시각
         * @param curfewTime    통금 시각
         * @param maxNameLength 이름 최대 길이
         * @param maxRoomLength 호수 최대 길이
         */
        public Registration(LocalTime openTime, LocalTime closeTime,
                            LocalTime returnTime, LocalTime curfewTime,
                            int maxNameLength, int maxRoomLength) {
            this(openTime, closeTime, returnTime, curfewTime, maxNameLength, maxRoomLength, null);
        }

        /**
         * 등록 마감 기본 시각(21:45).
         *
         * <p>application.yml 의 {@code imlate.registration.close-time} 기본값과 반드시 같아야 한다.
         * 둘이 어긋나면 "설정을 지운 환경"과 "설정을 둔 환경"의 마감 시각이 달라진다.
         */
        public static final LocalTime DEFAULT_CLOSE_TIME = LocalTime.of(21, 45);

        @ConstructorBinding
        public Registration {
            // 값이 없으면 SPEC 기본값으로 보정한다.
            if (openTime == null) {
                openTime = LocalTime.MIDNIGHT;
            }
            if (closeTime == null) {
                closeTime = DEFAULT_CLOSE_TIME;
            }
            if (returnTime == null) {
                returnTime = LocalTime.of(23, 30);
            }
            if (curfewTime == null) {
                curfewTime = LocalTime.of(22, 30);
            }
            if (maxNameLength <= 0) {
                maxNameLength = 20;
            }
            if (maxRoomLength <= 0) {
                maxRoomLength = 20;
            }
            if (cancel == null) {
                cancel = new Cancel(0, 0);
            }
        }
    }

    /**
     * 등록 취소 정책(`imlate.registration.cancel.*`).
     *
     * <p>취소 비밀번호는 숫자 {@code passwordLength} 자리다. 자릿수가 짧아 경우의 수가 적으므로
     * <b>총 시도 횟수 상한</b>이 실질적인 방어선이다({@code CancelAttemptGuard} 참고).
     *
     * @param passwordLength 취소 비밀번호 자릿수(기본 4)
     * @param maxAttempts    사람·날짜당 허용하는 최대 실패 횟수(기본 10)
     * @param hashIterations 비밀번호 해시(PBKDF2) 반복 횟수(기본 20,000)
     */
    public record Cancel(int passwordLength, int maxAttempts, int hashIterations) {

        /**
         * 반복 횟수 없이 만드는 축약 생성자(주로 테스트).
         *
         * <p>{@link Registration} 과 같은 이유로, 이 생성자가 생기면서 정규 생성자에
         * {@link ConstructorBinding} 이 필수가 되었다. 표시가 없으면 바인딩이 조용히 실패해
         * 설정을 무시하고 기본값만 쓴다.
         *
         * @param passwordLength 비밀번호 자릿수
         * @param maxAttempts    최대 실패 횟수
         */
        public Cancel(int passwordLength, int maxAttempts) {
            this(passwordLength, maxAttempts, 0);
        }

        /** 비밀번호 자릿수 기본값. 프론트 입력 필드와 서버 검증이 같은 값을 봐야 한다. */
        public static final int DEFAULT_PASSWORD_LENGTH = 4;

        /**
         * 하루 최대 실패 허용 횟수 기본값.
         *
         * <p>4자리(1만 가지)에서 10회면 적중 확률 0.1%. 본인이 잊었을 때 다시 떠올릴 여유는 주면서
         * 남의 등록을 대입으로 지우는 것은 사실상 불가능하게 만드는 지점이다.
         */
        public static final int DEFAULT_MAX_ATTEMPTS = 10;

        /**
         * PBKDF2 반복 횟수 기본값.
         *
         * <p><b>왜 20,000인가 — 일반적인 비밀번호 권고치(수십만 회)보다 훨씬 낮게 잡았고, 그 근거가 있다.</b>
         *
         * <p>이 값이 지키는 것은 "DB 와 서버 시크릿이 <u>동시에</u> 새어 나갔을 때 대입에 드는 비용"뿐이다.
         * DB 만 유출된 경우는 pepper({@code CancelPasswordHasher}) 때문에 반복 횟수와 무관하게 대입이
         * 아예 불가능하고, 온라인 대입은 {@code max-attempts}(하루 10회)가 막는다.
         * 게다가 비밀번호는 숫자 4자리(경우의 수 1만)이고 그 값은 하루면 의미가 사라진다 —
         * 반복 횟수를 아무리 올려도 이 조합이 강해지지는 않는다.
         *
         * <p>반대편 비용은 분명하다. 운영 인스턴스는 <b>t3.small(2 vCPU, 버스트형)</b>이고
         * 등록은 21:45 마감 직전에 몰린다. 측정해 보니 10만 회는 1건당 약 100ms(개발 PC 기준),
         * 운영 인스턴스에서는 150~200ms다. 마감 직전 수십 명이 동시에 누르면 vCPU 2개에 그대로 줄을 서고,
         * 프론트 타임아웃(10초)에 걸리는 사람이 생긴다. <u>등록에 실패한 교육생은 명단에서 빠져
         * 22:30 에 문 밖에 갇힌다</u> — 이 시스템이 존재하는 이유가 바로 그것을 막는 것이다.
         * 얻는 것이 거의 없는 보안 강화를 위해 그 위험을 살 이유가 없다.
         *
         * <p>20,000회는 1건당 약 20ms(개발 PC) / 35ms(t3.small) 수준이라 마감 직전 50명이 몰려도
         * 1초 안에 소화된다. 인스턴스를 키우면 설정으로 올릴 수 있고, 저장 형식에 반복 횟수가 들어 있어
         * <b>이미 저장된 해시는 그대로 검증된다</b>.
         */
        public static final int DEFAULT_HASH_ITERATIONS = 20_000;

        /** 반복 횟수 하한. 설정 실수로 사실상 해시가 없는 것과 다름없어지는 일을 막는다. */
        public static final int MIN_HASH_ITERATIONS = 1_000;

        @ConstructorBinding
        public Cancel {
            if (passwordLength <= 0) {
                passwordLength = DEFAULT_PASSWORD_LENGTH;
            }
            if (maxAttempts <= 0) {
                maxAttempts = DEFAULT_MAX_ATTEMPTS;
            }
            if (hashIterations <= 0) {
                hashIterations = DEFAULT_HASH_ITERATIONS;
            } else if (hashIterations < MIN_HASH_ITERATIONS) {
                hashIterations = MIN_HASH_ITERATIONS;
            }
        }
    }

    /**
     * Redis WAL 설정.
     *
     * @param keyPrefix WAL 키 접두어(기본 "imlate:wal")
     * @param ttlDays   WAL 보존 일수
     */
    public record Wal(String keyPrefix, int ttlDays) {

        public Wal {
            if (keyPrefix == null || keyPrefix.isBlank()) {
                keyPrefix = "imlate:wal";
            }
            if (ttlDays <= 0) {
                ttlDays = 7;
            }
        }
    }

    /**
     * 사감 조회 페이지 설정.
     *
     * @param baseUrl       조회 페이지 기준 URL(예: https://imlate.example.com)
     * @param tokenSecret   조회 토큰 HMAC 시크릿
     * @param tokenTtlHours 조회 토큰 유효 시간(시간 단위)
     */
    public record Lookup(String baseUrl, String tokenSecret, int tokenTtlHours) {

        public Lookup {
            if (baseUrl == null) {
                baseUrl = "";
            }
            if (tokenSecret == null) {
                tokenSecret = "";
            }
            if (tokenTtlHours <= 0) {
                tokenTtlHours = 48;
            }
        }
    }

    /**
     * 관리 API 설정.
     *
     * @param apiKey X-Admin-Key 헤더로 검증할 관리자 키
     */
    public record Admin(String apiKey) {

        public Admin {
            if (apiKey == null) {
                apiKey = "";
            }
        }
    }
}
