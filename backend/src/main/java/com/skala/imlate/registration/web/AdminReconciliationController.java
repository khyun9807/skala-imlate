package com.skala.imlate.registration.web;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.skala.imlate.common.security.AdminKeyGuard;
import com.skala.imlate.registration.service.ReconciliationReport;
import com.skala.imlate.registration.service.ReconciliationService;

/**
 * 운영자용 대사(WAL ↔ DB) 결과 조회 API.
 *
 * <p>대사 결과는 사감/교육생 화면에서는 감췄지만, 운영자는 언제든 확인할 수 있어야 하므로
 * {@code X-Admin-Key} 로 보호되는 이 경로를 통해 노출한다.
 *
 * <p>GET 은 부작용이 없어야 하므로 복구 없이 비교만 하는
 * {@link ReconciliationService#inspect(LocalDate)} 를 호출한다. 누락분 복구는 22:10 발송 경로
 * ({@code ReconciliationService#reconcile}) 에서 계속 수행된다.
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
public class AdminReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(AdminReconciliationController.class);

    private final AdminKeyGuard adminKeyGuard;
    private final ReconciliationService reconciliationService;
    private final Clock clock;

    /**
     * @param adminKeyGuard         관리자 키 검증
     * @param reconciliationService WAL ↔ DB 대사
     * @param clock                 서비스 기준 시계
     */
    public AdminReconciliationController(AdminKeyGuard adminKeyGuard,
                                         ReconciliationService reconciliationService,
                                         Clock clock) {
        this.adminKeyGuard = adminKeyGuard;
        this.reconciliationService = reconciliationService;
        this.clock = clock;
    }

    /**
     * 해당 일자의 대사 결과를 조회한다(복구는 하지 않는다).
     *
     * @param adminKey 관리자 키 헤더
     * @param date     대상일(생략 시 오늘)
     * @return 대사 결과
     */
    @GetMapping
    public ReconciliationReport inspect(
            @RequestHeader(value = AdminKeyGuard.HEADER_NAME, required = false) String adminKey,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        adminKeyGuard.require(adminKey);
        LocalDate target = date != null ? date : LocalDate.now(clock);
        ReconciliationReport report = reconciliationService.inspect(target);
        log.info("[ADMIN] 대사 조회: date={}, status={}, db={}, wal={}",
                target, report.status(), report.dbCount(), report.walCount());
        return report;
    }
}
