package com.skala.imlate.notification.ops;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.skala.imlate.notification.channel.SmsBalance;
import com.skala.imlate.notification.channel.SmsSender;

/**
 * 문자 잔여 건수 감시(위험 3).
 *
 * <p>잔액이 0 이 되면 21:50 발송이 통째로 실패하지만 지금은 그 사실이 조용하다. 발송 <b>직후</b>
 * 한 번만 조회해 임계값 미만이면 WARN 로그를 남기고, 운영자 알림에 붙일 한 줄을 돌려준다.
 *
 * <p><b>하루 1회</b>만 조회한다. 재시도(22:05 / 22:20)까지 매번 조회하면 발송 경로에 외부 호출이
 * 늘어날 뿐 얻는 정보가 없다. 같은 날 두 번째 호출부터는 처음 조회한 결과를 그대로 돌려준다.
 *
 * <p>조회 실패는 <b>전부 무시</b>한다(로그만). 잔액을 못 읽었다고 발송 결과가 달라지면 안 된다.
 */
@Component
public class SmsBalanceMonitor {

    private static final Logger log = LoggerFactory.getLogger(SmsBalanceMonitor.class);

    /** 잔액 경고 임계값 기본값(건). */
    public static final int DEFAULT_LOW_BALANCE_THRESHOLD = 100;

    private final SmsSender smsSender;
    private final int lowBalanceThreshold;

    /**
     * 마지막 조회 결과. 날짜와 경고 문구를 한 객체로 묶어 원자적으로 교체한다
     * (날짜와 문구를 따로 두면 재시도 스레드와 엇갈릴 수 있다).
     */
    private final AtomicReference<Checked> lastChecked = new AtomicReference<>();

    /**
     * @param smsSender           문자 발송기(잔액 조회를 지원하지 않으면 아무것도 하지 않는다)
     * @param lowBalanceThreshold 경고 임계값. 0 이하면 감시하지 않는다.
     *                            <p>키는 {@code imlate.sms.aligo.low-balance-threshold} 다.
     *                            {@code SmsProperties} 는 이 작업의 소유 파일이 아니라 레코드를
     *                            건드리지 않고 {@code @Value} 로 같은 키를 직접 읽는다.
     */
    public SmsBalanceMonitor(SmsSender smsSender,
                             @Value("${imlate.sms.aligo.low-balance-threshold:"
                                     + DEFAULT_LOW_BALANCE_THRESHOLD + "}") int lowBalanceThreshold) {
        this.smsSender = smsSender;
        this.lowBalanceThreshold = lowBalanceThreshold;
    }

    /**
     * 그날 처음이면 잔액을 조회하고, 이미 조회했으면 그 결과를 재사용한다.
     *
     * @param date 발송 대상일
     * @return 임계값 미만일 때의 경고 문구(정상이거나 조회 불가면 비어 있음)
     */
    public Optional<String> checkOnce(LocalDate date) {
        if (lowBalanceThreshold <= 0) {
            return Optional.empty();
        }
        Checked previous = lastChecked.get();
        if (previous != null && previous.date().equals(date)) {
            // 하루 1회 원칙: 재시도에서는 다시 조회하지 않는다.
            return Optional.ofNullable(previous.warning());
        }

        String warning = null;
        try {
            Optional<SmsBalance> balance = smsSender.remainingBalance();
            if (balance.isPresent()) {
                warning = evaluate(balance.get());
            } else {
                log.debug("문자 잔여 건수를 조회할 수 없어 잔액 감시를 건너뜁니다. provider={}",
                        smsSender.providerName());
            }
        } catch (Exception ex) {
            // 계약상 발송기는 예외를 던지지 않지만, 던지더라도 여기서 흡수한다.
            log.warn("문자 잔여 건수 조회 중 오류(무시). cause={}", ex.toString());
        }
        lastChecked.set(new Checked(date, warning));
        return Optional.ofNullable(warning);
    }

    /** 임계값 판정. 미만이면 WARN 로그를 남기고 알림에 붙일 문구를 만든다. */
    private String evaluate(SmsBalance balance) {
        int remaining = balance.effectiveRemaining();
        if (remaining >= lowBalanceThreshold) {
            log.info("문자 잔여 건수: {} (임계값 {}건)", balance.describe(), lowBalanceThreshold);
            return null;
        }
        log.warn("문자 잔여 건수가 임계값 미만입니다. 잔여={}, 임계값={}건 — 충전하지 않으면 발송이 실패합니다.",
                balance.describe(), lowBalanceThreshold);
        return "문자 잔여 건수 부족: " + balance.describe()
                + " (임계값 " + lowBalanceThreshold + "건) — 충전하지 않으면 다음 발송이 실패합니다.";
    }

    /**
     * 그날의 조회 결과.
     *
     * @param date    조회한 날짜
     * @param warning 경고 문구(정상이면 null)
     */
    private record Checked(LocalDate date, String warning) {
    }
}
