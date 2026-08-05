package com.skala.imlate.common.error;

import java.io.Serial;

/**
 * 사용자에게 그대로 노출해도 되는 업무 예외. {@link GlobalExceptionHandler} 가 {@link ErrorResponse} 로 변환한다.
 */
public class ApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code == null ? ErrorCode.INTERNAL_ERROR : code;
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? ErrorCode.INTERNAL_ERROR : code;
    }

    /** 정적 팩토리. */
    public static ApiException of(ErrorCode code, String message) {
        return new ApiException(code, message);
    }

    /** 원인 예외를 포함하는 정적 팩토리. */
    public static ApiException of(ErrorCode code, String message, Throwable cause) {
        return new ApiException(code, message, cause);
    }

    /** 이 예외의 에러 코드. */
    public ErrorCode code() {
        return code;
    }
}
