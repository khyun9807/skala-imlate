package com.skala.imlate.registration.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import com.skala.imlate.common.web.ClientIpResolver;
import com.skala.imlate.registration.service.CancelCommand;
import com.skala.imlate.registration.service.CancelResult;
import com.skala.imlate.registration.service.RegistrationCommand;
import com.skala.imlate.registration.service.RegistrationResult;
import com.skala.imlate.registration.service.RegistrationService;
import com.skala.imlate.registration.service.RegistrationWindow;
import com.skala.imlate.registration.service.RegistrationWindowPolicy;
import com.skala.imlate.registration.web.dto.CancelRequest;
import com.skala.imlate.registration.web.dto.CancelResponse;
import com.skala.imlate.registration.web.dto.RegistrationRequest;
import com.skala.imlate.registration.web.dto.RegistrationResponse;
import com.skala.imlate.registration.web.dto.RegistrationSummaryResponse;
import com.skala.imlate.registration.web.dto.YesterdayResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 교육생용 등록 API(SPEC §5.5). 컨트롤러는 얇게 유지하고 업무 판단은 서비스에 위임한다.
 */
@RestController
@RequestMapping("/api/v1/registrations")
public class RegistrationController {

    private static final Logger log = LoggerFactory.getLogger(RegistrationController.class);

    private final RegistrationService registrationService;
    private final RegistrationWindowPolicy windowPolicy;

    /**
     * @param registrationService 등록 서비스
     * @param windowPolicy        등록 창 정책
     */
    public RegistrationController(RegistrationService registrationService, RegistrationWindowPolicy windowPolicy) {
        this.registrationService = registrationService;
        this.windowPolicy = windowPolicy;
    }

    /**
     * 23:30 복귀 등록. 신규면 201, 이미 등록된 경우면 200 + {@code duplicate=true}.
     *
     * @param request     등록 요청 본문
     * @param httpRequest 클라이언트 IP 추출용
     * @return 등록 결과
     */
    @PostMapping
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request,
                                                          HttpServletRequest httpRequest) {
        String clientIp = ClientIpResolver.resolve(httpRequest);
        RegistrationResult result = registrationService.register(new RegistrationCommand(
                request.className(), request.studentName(), request.roomNumber(),
                request.cancelPassword(), clientIp));

        RegistrationResponse body = RegistrationResponse.of(
                result.registration(), result.duplicate(), windowPolicy.returnTime());
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        log.debug("Registration response status={} duplicate={}", status.value(), result.duplicate());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 등록 취소. 반·이름·호수로 등록을 찾고 등록할 때 정한 비밀번호로 본인임을 확인한다.
     *
     * <p><b>왜 {@code DELETE} 가 아니라 {@code POST} 인가</b> — 취소에는 비밀번호가 필요한데,
     * {@code DELETE} 의 본문은 명세상 의미가 정의되어 있지 않아 프록시·클라이언트가 버릴 수 있다.
     * 비밀번호를 URL 쿼리로 옮기면 접근 로그·브라우저 히스토리에 평문으로 남는다.
     * 그래서 본문을 확실히 실어 보낼 수 있는 {@code POST} 를 쓴다.
     *
     * <p>경로가 {@code /api/v1/registrations} 로 시작하므로 등록과 같은 rate limit 스코프를 탄다
     * (IP 축 + 개인 축). 비밀번호 대입은 그와 별개로 {@code CancelAttemptGuard} 가 총량으로 막는다.
     *
     * @param request     취소 요청 본문
     * @param httpRequest 클라이언트 IP 추출용
     * @return 취소 결과(항상 200. 실패는 예외 → 에러 응답)
     */
    @PostMapping("/cancel")
    public CancelResponse cancel(@Valid @RequestBody CancelRequest request,
                                 HttpServletRequest httpRequest) {
        String clientIp = ClientIpResolver.resolve(httpRequest);
        CancelResult result = registrationService.cancel(new CancelCommand(
                request.className(), request.studentName(), request.roomNumber(),
                request.password(), clientIp));
        log.debug("Cancel response alreadyCancelled={}", result.alreadyCancelled());
        return CancelResponse.of(result);
    }

    /**
     * 등록 창 상태. 프론트가 <b>서버 시간</b> 기준으로 마감 카운트다운을 표시한다.
     *
     * @return 등록 창 상태
     */
    @GetMapping("/window")
    public RegistrationWindow window() {
        return windowPolicy.describe();
    }

    /**
     * 공개용 등록 현황 요약(PII 없음).
     *
     * <p>등록 인원 수는 사감만 알면 되는 정보이므로 내려주지 않는다(집계는 서버에서 계속 유지된다).
     *
     * @return 오늘 날짜·등록 가능 여부
     */
    @GetMapping("/summary")
    public RegistrationSummaryResponse summary() {
        return new RegistrationSummaryResponse(windowPolicy.targetDate(), windowPolicy.isOpen());
    }

    /**
     * 어제 연장 복귀 인원 수(공개, PII 없음).
     *
     * <p>등록 화면 하단 안내 문구("어제 N명이 …")에 쓴다. 취소분은 빠진 최종 인원이다.
     *
     * <p><b>"어제"는 오늘 대상일에서 하루를 뺀 날이다.</b> 등록 창이 자정에 열리므로
     * 00:10 에 접속하면 대상일은 이미 오늘이고, 어제는 방금 23:30 에 문이 열렸던 그날이 된다 —
     * 화면이 말하려는 "어제"와 정확히 일치한다.
     *
     * @return 어제 날짜와 인원 수
     */
    @GetMapping("/yesterday")
    public YesterdayResponse yesterday() {
        LocalDate target = windowPolicy.targetDate().minusDays(1);
        return new YesterdayResponse(target, registrationService.countByDate(target));
    }
}
