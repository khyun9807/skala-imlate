package com.skala.imlate.common.properties;

import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
     * @param openTime      등록 시작 시각(포함, 기본 00:00)
     * @param closeTime     등록 마감 시각(미포함, 기본 22:00)
     * @param returnTime    연장 복귀 시각(기본 23:30) — 안내 문구용
     * @param curfewTime    원래 통금 시각(기본 22:30) — 안내 문구용
     * @param maxNameLength 이름 최대 길이
     * @param maxRoomLength 호수 최대 길이
     */
    public record Registration(LocalTime openTime, LocalTime closeTime,
                               LocalTime returnTime, LocalTime curfewTime,
                               int maxNameLength, int maxRoomLength) {

        public Registration {
            // 값이 없으면 SPEC 기본값으로 보정한다.
            if (openTime == null) {
                openTime = LocalTime.MIDNIGHT;
            }
            if (closeTime == null) {
                closeTime = LocalTime.of(22, 0);
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
