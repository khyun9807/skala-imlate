package com.skala.imlate.common.error;

import org.springframework.http.HttpStatus;

/**
 * API 에러 코드. 코드 이름이 그대로 응답 본문의 {@code code} 필드가 된다.
 */
public enum ErrorCode {

    /** 요청 값 검증 실패. */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    /** 등록 마감 시각(22:00) 이후 요청. */
    REGISTRATION_CLOSED(HttpStatus.CONFLICT),
    /** 등록 시작 시각 이전 요청. */
    REGISTRATION_NOT_OPEN(HttpStatus.CONFLICT),
    /** 인증 실패(관리자 키 불일치 등). */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    /** 권한 없음(조회 토큰 불일치·만료 등). */
    FORBIDDEN(HttpStatus.FORBIDDEN),
    /** 대상 리소스 없음. */
    NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 요청 과다(rate limit). */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    /** 외부 API(Aligo·SES 등) 오류. */
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY),
    /** 서버 내부 오류. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    /** 이 에러 코드에 대응하는 HTTP 상태 코드. */
    public HttpStatus status() {
        return status;
    }
}
