package com.skala.imlate.registration.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.skala.imlate.common.error.ApiException;
import com.skala.imlate.common.error.ErrorCode;
import com.skala.imlate.common.properties.ImlateProperties;

/**
 * 등록 창 정책(SPEC §3, §5.3).
 *
 * <p>등록 가능 구간은 {@code [open-time, close-time)} 이며 기본값은 {@code [00:00, 22:00)} 이다.
 * 22:00:00 정각부터는 등록을 거부한다.
 *
 * <p>모든 시각 계산은 주입받은 {@link Clock} 으로만 한다(인자 없는 {@code now()} 금지).
 */
@Component
public class RegistrationWindowPolicy {

    /** 사용자 안내 문구에 쓰는 시각 형식(예: 22:00). */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final Clock clock;
    private final LocalTime openTime;
    private final LocalTime closeTime;
    private final LocalTime returnTime;
    private final LocalTime curfewTime;

    /**
     * @param properties {@code imlate.registration.*} 설정
     * @param clock      서비스 기준 시계(Asia/Seoul)
     */
    public RegistrationWindowPolicy(ImlateProperties properties, Clock clock) {
        this.clock = clock;
        ImlateProperties.Registration registration = properties.registration();
        this.openTime = registration.openTime();
        this.closeTime = registration.closeTime();
        this.returnTime = registration.returnTime();
        this.curfewTime = registration.curfewTime();
    }

    /**
     * 복귀 대상일 = 등록 시점의 KST 날짜.
     *
     * @return 오늘 날짜(KST)
     */
    public LocalDate targetDate() {
        return LocalDate.now(clock);
    }

    /**
     * 지금 등록이 가능한지.
     *
     * @return 등록 창이 열려 있으면 true
     */
    public boolean isOpen() {
        LocalTime now = LocalTime.now(clock);
        return !now.isBefore(openTime) && now.isBefore(closeTime);
    }

    /**
     * 등록 창이 닫혀 있으면 예외를 던진다.
     *
     * @throws ApiException 시작 전이면 {@link ErrorCode#REGISTRATION_NOT_OPEN},
     *                      마감 후면 {@link ErrorCode#REGISTRATION_CLOSED}
     */
    public void requireOpen() {
        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(openTime)) {
            throw ApiException.of(ErrorCode.REGISTRATION_NOT_OPEN,
                    "등록은 " + TIME_FORMAT.format(openTime) + "부터 가능합니다.");
        }
        if (!now.isBefore(closeTime)) {
            throw ApiException.of(ErrorCode.REGISTRATION_CLOSED,
                    "등록 마감 시간(" + TIME_FORMAT.format(closeTime) + ")이 지났습니다.");
        }
    }

    /**
     * 프론트 카운트다운용 등록 창 상태.
     *
     * @return 서버 시각·마감 시각·남은 초를 담은 DTO
     */
    public RegistrationWindow describe() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate date = now.toLocalDate();
        OffsetDateTime opensAt = date.atTime(openTime).atZone(clock.getZone()).toOffsetDateTime();
        OffsetDateTime closesAt = date.atTime(closeTime).atZone(clock.getZone()).toOffsetDateTime();
        long secondsUntilClose = Math.max(0L, Duration.between(now.toInstant(), closesAt.toInstant()).getSeconds());
        return new RegistrationWindow(date, isOpen(), now.toOffsetDateTime(), opensAt, closesAt,
                returnTime, curfewTime, secondsUntilClose);
    }

    /** 연장 복귀 시각(기본 23:30). 안내 문구·응답에 사용한다. */
    public LocalTime returnTime() {
        return returnTime;
    }

    /** 원래 통금 시각(기본 22:30). 안내 문구에 사용한다. */
    public LocalTime curfewTime() {
        return curfewTime;
    }

    /** 등록 마감 시각(기본 22:00). */
    public LocalTime closeTime() {
        return closeTime;
    }
}
