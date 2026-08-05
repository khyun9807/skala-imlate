package com.skala.imlate.common.error;

import org.springframework.http.HttpStatus;

/**
 * API 에러 코드. 코드 이름이 그대로 응답 본문의 {@code code} 필드가 된다.
 */
public enum ErrorCode {

    /** 요청 값 검증 실패. */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    /** 등록 마감 시각(21:45) 이후 요청. */
    REGISTRATION_CLOSED(HttpStatus.CONFLICT),
    /** 등록 시작 시각 이전 요청. */
    REGISTRATION_NOT_OPEN(HttpStatus.CONFLICT),
    /** 인증 실패(관리자 키 불일치 등). */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    /** 권한 없음(조회 토큰 불일치·만료 등). */
    FORBIDDEN(HttpStatus.FORBIDDEN),
    /** 대상 리소스 없음. */
    NOT_FOUND(HttpStatus.NOT_FOUND),
    /**
     * 취소 요청이 거부됨(등록이 없거나 비밀번호가 다름).
     *
     * <p><b>두 경우를 하나의 코드로 합친 것은 의도한 설계다.</b> 나누면 "등록 없음"과 "비밀번호 틀림"의
     * 응답 차이로 <u>누가 오늘 등록했는지</u>를 밖에서 알아낼 수 있다(존재 여부 노출).
     * 명단은 사감만 보는 정보이므로 취소 실패는 항상 같은 코드·같은 문구로 답한다.
     */
    CANCEL_REJECTED(HttpStatus.BAD_REQUEST),
    /** 취소 비밀번호 실패 횟수 초과(그날은 더 시도할 수 없다). */
    CANCEL_LOCKED(HttpStatus.TOO_MANY_REQUESTS),
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
