package com.skala.imlate.common.error;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

/**
 * 전역 예외 처리기. 모든 오류를 {@link ErrorResponse} 형태로 통일한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /** 업무 예외. 메시지를 그대로 사용자에게 노출한다. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.code();
        if (code.status().is5xxServerError()) {
            log.error("ApiException(5xx) path={} code={} message={}", request.getRequestURI(), code, ex.getMessage(), ex);
        } else {
            log.warn("ApiException path={} code={} message={}", request.getRequestURI(), code, ex.getMessage());
        }
        return build(code.status(), code.name(), ex.getMessage(), request, List.of());
    }

    /** {@code @Valid} 바디 검증 실패 → 필드별 메시지 매핑. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                      HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.add(new ErrorResponse.FieldError(fieldError.getField(), defaultMessage(fieldError)));
        }
        for (ObjectError globalError : ex.getBindingResult().getGlobalErrors()) {
            errors.add(new ErrorResponse.FieldError(globalError.getObjectName(), defaultMessage(globalError)));
        }
        log.warn("Validation failed path={} errors={}", request.getRequestURI(), errors);
        return build(ErrorCode.VALIDATION_FAILED.status(), ErrorCode.VALIDATION_FAILED.name(),
                "입력값을 다시 확인해 주세요.", request, errors);
    }

    /** {@code @Validated} 파라미터 제약 위반. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                    HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        if (ex.getConstraintViolations() != null) {
            for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
                errors.add(new ErrorResponse.FieldError(lastPathNode(violation), violation.getMessage()));
            }
        }
        log.warn("Constraint violation path={} errors={}", request.getRequestURI(), errors);
        return build(ErrorCode.VALIDATION_FAILED.status(), ErrorCode.VALIDATION_FAILED.name(),
                "입력값을 다시 확인해 주세요.", request, errors);
    }

    /** 요청 본문 JSON 파싱 실패. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex,
                                                            HttpServletRequest request) {
        log.warn("Malformed request body path={} message={}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.VALIDATION_FAILED.status(), ErrorCode.VALIDATION_FAILED.name(),
                "요청 본문을 읽을 수 없습니다. 형식을 확인해 주세요.", request, List.of());
    }

    /** 필수 쿼리 파라미터 누락. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex,
                                                                 HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors =
                List.of(new ErrorResponse.FieldError(ex.getParameterName(), "필수 파라미터입니다."));
        log.warn("Missing request parameter path={} name={}", request.getRequestURI(), ex.getParameterName());
        return build(ErrorCode.VALIDATION_FAILED.status(), ErrorCode.VALIDATION_FAILED.name(),
                "필수 파라미터가 누락되었습니다.", request, errors);
    }

    /** 파라미터 타입 변환 실패(예: date=abc). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                             HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors =
                List.of(new ErrorResponse.FieldError(ex.getName(), "형식이 올바르지 않습니다."));
        log.warn("Parameter type mismatch path={} name={} value={}", request.getRequestURI(), ex.getName(), ex.getValue());
        return build(ErrorCode.VALIDATION_FAILED.status(), ErrorCode.VALIDATION_FAILED.name(),
                "파라미터 형식이 올바르지 않습니다.", request, errors);
    }

    /** 존재하지 않는 정적 리소스/경로. 500 으로 새어 나가지 않도록 명시적으로 404 처리한다. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex,
                                                                HttpServletRequest request) {
        log.debug("No resource found path={}", request.getRequestURI());
        return build(ErrorCode.NOT_FOUND.status(), ErrorCode.NOT_FOUND.name(),
                "요청하신 경로를 찾을 수 없습니다.", request, List.of());
    }

    /** 지원하지 않는 HTTP 메서드. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                   HttpServletRequest request) {
        log.warn("Method not supported path={} method={}", request.getRequestURI(), ex.getMethod());
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "지원하지 않는 요청 방식입니다.", request, List.of());
    }

    /** 그 밖의 모든 예외. 스택은 로그로만 남기고 응답에는 상세를 노출하지 않는다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception path={} type={}", request.getRequestURI(), ex.getClass().getName(), ex);
        return build(ErrorCode.INTERNAL_ERROR.status(), ErrorCode.INTERNAL_ERROR.name(),
                "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", request, List.of());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                HttpServletRequest request,
                                                List<ErrorResponse.FieldError> errors) {
        OffsetDateTime timestamp = OffsetDateTime.now(clock);
        String path = request == null ? "" : request.getRequestURI();
        String safeMessage = (message == null || message.isBlank()) ? "요청을 처리할 수 없습니다." : message;
        return ResponseEntity.status(status).body(new ErrorResponse(code, safeMessage, path, timestamp, errors));
    }

    private static String defaultMessage(ObjectError error) {
        String message = error.getDefaultMessage();
        return (message == null || message.isBlank()) ? "올바르지 않은 값입니다." : message;
    }

    private static String lastPathNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        int idx = path.lastIndexOf('.');
        return idx >= 0 && idx < path.length() - 1 ? path.substring(idx + 1) : path;
    }
}
