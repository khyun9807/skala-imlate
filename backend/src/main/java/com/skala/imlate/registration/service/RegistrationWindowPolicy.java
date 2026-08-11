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
 * <p>등록 가능 구간은 {@code [open-time, close-time)} 이며 기본값은 {@code [00:00, 22:15)} 이다.
 * 22:15:00 정각부터는 등록을 거부한다.
 *
 * <p><b>취소 창은 등록 창보다 넓다</b> — {@code [open-time, cancel-close-time)}, 기본 {@code [00:00, 22:20)}.
 * 마감 직전에 등록하고 곧바로 마음이 바뀐 사람에게 되돌릴 5분을 주기 위해서다.
 * 취소 마감(22:20)은 사감 발송(22:25)보다 앞이라, 취소분이 명단에 반영되지 못하는 구간은 없다.
 *
 * <p>모든 시각 계산은 주입받은 {@link Clock} 으로만 한다(인자 없는 {@code now()} 금지).
 */
@Component
public class RegistrationWindowPolicy {

    /** 사용자 안내 문구에 쓰는 시각 형식(예: 22:15). */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final Clock clock;
    private final LocalTime openTime;
    private final LocalTime closeTime;
    private final LocalTime cancelCloseTime;
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
        this.cancelCloseTime = registration.cancelCloseTime();
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
     * 지금 취소가 가능한지.
     *
     * @return 취소 창이 열려 있으면 true
     */
    public boolean isCancelOpen() {
        LocalTime now = LocalTime.now(clock);
        return !now.isBefore(openTime) && now.isBefore(cancelCloseTime);
    }

    /**
     * 취소 창이 닫혀 있으면 예외를 던진다.
     *
     * <p>등록 창과 <b>별도의 마감</b>을 본다. 등록 마감(22:15)과 취소 마감(22:20) 사이 5분 동안은
     * 새 등록은 거부되지만 이미 한 등록의 취소는 받아 준다.
     *
     * <p>문구가 "등록 취소 마감"이라고 명시하는 것은 의도한 것이다. 등록 마감과 시각이 다르므로
     * 그냥 "마감 시간이 지났습니다"라고만 하면 교육생이 어느 마감을 말하는지 알 수 없다.
     *
     * @throws ApiException 시작 전이면 {@link ErrorCode#REGISTRATION_NOT_OPEN},
     *                      취소 마감 후면 {@link ErrorCode#REGISTRATION_CLOSED}
     */
    public void requireCancelOpen() {
        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(openTime)) {
            throw ApiException.of(ErrorCode.REGISTRATION_NOT_OPEN,
                    "등록은 " + TIME_FORMAT.format(openTime) + "부터 가능합니다.");
        }
        if (!now.isBefore(cancelCloseTime)) {
            throw ApiException.of(ErrorCode.REGISTRATION_CLOSED,
                    "등록 취소 마감 시간(" + TIME_FORMAT.format(cancelCloseTime) + ")이 지났습니다.");
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
        OffsetDateTime cancelClosesAt =
                date.atTime(cancelCloseTime).atZone(clock.getZone()).toOffsetDateTime();
        long secondsUntilClose = Math.max(0L, Duration.between(now.toInstant(), closesAt.toInstant()).getSeconds());
        long secondsUntilCancelClose =
                Math.max(0L, Duration.between(now.toInstant(), cancelClosesAt.toInstant()).getSeconds());
        return new RegistrationWindow(date, isOpen(), now.toOffsetDateTime(), opensAt, closesAt,
                returnTime, curfewTime, secondsUntilClose,
                isCancelOpen(), cancelClosesAt, secondsUntilCancelClose);
    }

    /** 연장 복귀 시각(기본 23:30). 안내 문구·응답에 사용한다. */
    public LocalTime returnTime() {
        return returnTime;
    }

    /** 원래 통금 시각(기본 22:30). 안내 문구에 사용한다. */
    public LocalTime curfewTime() {
        return curfewTime;
    }

    /** 등록 마감 시각(기본 22:15). */
    public LocalTime closeTime() {
        return closeTime;
    }

    /** 취소 마감 시각(기본 22:20). 등록 마감보다 이르지 않다. */
    public LocalTime cancelCloseTime() {
        return cancelCloseTime;
    }
}
