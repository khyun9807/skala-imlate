package com.skala.imlate.common.error;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 모든 API 오류 응답의 공통 본문.
 *
 * @param code      에러 코드 이름(예: REGISTRATION_CLOSED)
 * @param message   사용자에게 보여줄 한국어 메시지
 * @param path      요청 경로
 * @param timestamp 서버 기준 발생 시각(KST 오프셋 포함)
 * @param errors    필드 단위 검증 오류 목록(없으면 빈 목록)
 */
public record ErrorResponse(String code, String message, String path, OffsetDateTime timestamp,
                            List<FieldError> errors) {

    public ErrorResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /**
     * 필드 단위 검증 오류.
     *
     * @param field   필드명
     * @param message 오류 메시지
     */
    public record FieldError(String field, String message) {
    }

    /** 필드 오류가 없는 응답을 만든다. */
    public static ErrorResponse of(ErrorCode code, String message, String path, OffsetDateTime timestamp) {
        return new ErrorResponse(code.name(), message, path, timestamp, List.of());
    }

    /** 필드 오류를 포함한 응답을 만든다. */
    public static ErrorResponse of(ErrorCode code, String message, String path, OffsetDateTime timestamp,
                                   List<FieldError> errors) {
        return new ErrorResponse(code.name(), message, path, timestamp, errors);
    }
}
