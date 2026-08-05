package com.skala.imlate.registration.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.skala.imlate.common.error.ApiException;
import com.skala.imlate.common.error.ErrorCode;
import com.skala.imlate.common.security.AccessTokenService;
import com.skala.imlate.registration.domain.ReturnRegistration;
import com.skala.imlate.registration.service.ReconciliationReport;
import com.skala.imlate.registration.service.ReconciliationService;
import com.skala.imlate.registration.service.RegistrationService;
import com.skala.imlate.registration.service.RegistrationWindowPolicy;
import com.skala.imlate.registration.web.dto.LookupResponse;
import com.skala.imlate.stats.StatsQueryService;
import com.skala.imlate.stats.StatsSnapshot;

/**
 * 사감용 조회 페이지 API(R8, R9 / SPEC §5.5).
 *
 * <p>{@code GET /api/v1/lookup?date=YYYY-MM-DD&token=…} — 토큰은
 * {@link AccessTokenService#requireValid(LocalDate, String)} 로 검증한다(실패 시 403).
 * 명단·집계·WAL 대사 결과·방문 통계를 조립해 한 번에 내려준다.
 */
@RestController
@RequestMapping("/api/v1/lookup")
public class LookupController {

    private static final Logger log = LoggerFactory.getLogger(LookupController.class);

    /** 호수는 숫자면 숫자 크기 순, 아니면 문자열 순으로 정렬한다(예: 302 < 1002). */
    private static final Comparator<String> ROOM_ORDER = (left, right) -> {
        Integer a = toIntOrNull(left);
        Integer b = toIntOrNull(right);
        if (a != null && b != null) {
            int numeric = Integer.compare(a, b);
            // 숫자가 같아도 문자열이 다르면("302" vs "0302") 서로 다른 키로 유지한다(TreeMap 키 충돌 방지).
            return numeric != 0 ? numeric : left.compareTo(right);
        }
        if (a != null) {
            return -1;
        }
        if (b != null) {
            return 1;
        }
        return left.compareTo(right);
    };

    private final RegistrationService registrationService;
    private final ReconciliationService reconciliationService;
    private final RegistrationWindowPolicy windowPolicy;
    private final AccessTokenService accessTokenService;
    private final StatsQueryService statsQueryService;
    private final Clock clock;

    /**
     * @param registrationService   등록 조회
     * @param reconciliationService WAL ↔ DB 대사(복구 없는 inspect 만 사용)
     * @param windowPolicy          기준 날짜·안내 시각
     * @param accessTokenService    조회 토큰 검증
     * @param statsQueryService     통계 조회(stats 모듈)
     * @param clock                 서비스 기준 시계
     */
    public LookupController(RegistrationService registrationService,
                            ReconciliationService reconciliationService,
                            RegistrationWindowPolicy windowPolicy,
                            AccessTokenService accessTokenService,
                            StatsQueryService statsQueryService,
                            Clock clock) {
        this.registrationService = registrationService;
        this.reconciliationService = reconciliationService;
        this.windowPolicy = windowPolicy;
        this.accessTokenService = accessTokenService;
        this.statsQueryService = statsQueryService;
        this.clock = clock;
    }

    /**
     * 사감용 조회 데이터를 조립해 반환한다.
     *
     * @param date  조회 대상일(생략 시 오늘). 미래 날짜는 400
     * @param token 조회 토큰(필수, 검증 실패 시 403)
     * @return 명단·집계·대사·통계
     */
    @GetMapping
    public LookupResponse lookup(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "token", required = false) String token) {

        LocalDate today = windowPolicy.targetDate();
        LocalDate target = (date == null) ? today : date;
        if (target.isAfter(today)) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "미래 날짜는 조회할 수 없습니다.");
        }

        // 토큰 검증 (실패·미지정 모두 403)
        accessTokenService.requireValid(target, token);

        ReconciliationReport verification = reconciliationService.inspect(target);
        List<ReturnRegistration> rows = registrationService.findByDate(target);

        List<LookupResponse.Item> items = new ArrayList<>(rows.size());
        Map<String, Long> classCounts = new TreeMap<>();
        Map<String, Long> roomCounts = new TreeMap<>(ROOM_ORDER);
        int no = 1;
        for (ReturnRegistration row : rows) {
            // no 는 반 → 이름 정렬 순서 기준으로 1부터 부여한다.
            items.add(new LookupResponse.Item(no++, row.getClassName(), row.getStudentName(),
                    row.getRoomNumber(), row.getRegisteredAt()));
            classCounts.merge(row.getClassName(), 1L, Long::sum);
            roomCounts.merge(row.getRoomNumber(), 1L, Long::sum);
        }

        List<LookupResponse.ClassCount> byClass = classCounts.entrySet().stream()
                .map(entry -> new LookupResponse.ClassCount(entry.getKey(), entry.getValue()))
                .toList();
        List<LookupResponse.RoomCount> byRoom = roomCounts.entrySet().stream()
                .map(entry -> new LookupResponse.RoomCount(entry.getKey(), entry.getValue()))
                .toList();

        log.info("Lookup served date={} count={} verification={}", target, rows.size(), verification.status());
        return new LookupResponse(target, OffsetDateTime.now(clock), rows.size(),
                windowPolicy.returnTime(), windowPolicy.curfewTime(),
                items, byClass, byRoom, verification, safeStats());
    }

    /** 통계 조회는 조회 페이지의 부가 정보이므로 실패해도 0 으로 대체한다(가용성 우선). */
    private StatsSnapshot safeStats() {
        try {
            StatsSnapshot snapshot = statsQueryService.snapshot();
            return snapshot == null ? emptyStats() : snapshot;
        } catch (RuntimeException ex) {
            log.warn("Stats snapshot failed — 0 으로 대체합니다. cause={}", ex.toString());
            return emptyStats();
        }
    }

    private static StatsSnapshot emptyStats() {
        return new StatsSnapshot(0L, 0L, 0L, 0L, 0L, 0L);
    }

    private static Integer toIntOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            // 자릿수가 int 범위를 넘는 경우 — 문자열 순으로 처리한다.
            return null;
        }
    }
}
