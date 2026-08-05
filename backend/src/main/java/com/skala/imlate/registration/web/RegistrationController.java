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

import com.skala.imlate.common.web.ClientIpResolver;
import com.skala.imlate.registration.service.RegistrationCommand;
import com.skala.imlate.registration.service.RegistrationResult;
import com.skala.imlate.registration.service.RegistrationService;
import com.skala.imlate.registration.service.RegistrationWindow;
import com.skala.imlate.registration.service.RegistrationWindowPolicy;
import com.skala.imlate.registration.web.dto.RegistrationRequest;
import com.skala.imlate.registration.web.dto.RegistrationResponse;
import com.skala.imlate.registration.web.dto.RegistrationSummaryResponse;

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
                request.className(), request.studentName(), request.roomNumber(), clientIp));

        RegistrationResponse body = RegistrationResponse.of(
                result.registration(), result.duplicate(), windowPolicy.returnTime());
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        log.debug("Registration response status={} duplicate={}", status.value(), result.duplicate());
        return ResponseEntity.status(status).body(body);
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
}
