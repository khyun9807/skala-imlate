package com.skala.imlate.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.skala.imlate.common.error.ApiException;
import com.skala.imlate.common.error.ErrorCode;
import com.skala.imlate.common.properties.ImlateProperties;

/**
 * 관리 API 보호용 {@code X-Admin-Key} 헤더 검증기.
 */
@Component
public class AdminKeyGuard {

    private static final Logger log = LoggerFactory.getLogger(AdminKeyGuard.class);
    /** 관리 API 헤더 이름. 컨트롤러에서 재사용한다. */
    public static final String HEADER_NAME = "X-Admin-Key";

    private final String apiKey;

    public AdminKeyGuard(ImlateProperties properties) {
        this.apiKey = properties.admin().apiKey();
        if (this.apiKey.isBlank()) {
            log.warn("imlate.admin.api-key 가 비어 있습니다. 관리 API 는 모두 401 로 거부됩니다.");
        }
    }

    /** 헤더 값이 설정된 관리자 키와 일치하지 않으면 {@link ErrorCode#UNAUTHORIZED} 예외를 던진다. */
    public void require(String headerValue) {
        if (apiKey.isBlank()) {
            throw ApiException.of(ErrorCode.UNAUTHORIZED, "관리자 키가 설정되지 않아 요청을 처리할 수 없습니다.");
        }
        if (headerValue == null || headerValue.isBlank()) {
            throw ApiException.of(ErrorCode.UNAUTHORIZED, "관리자 인증 헤더가 필요합니다.");
        }
        byte[] expected = apiKey.getBytes(StandardCharsets.UTF_8);
        byte[] provided = headerValue.trim().getBytes(StandardCharsets.UTF_8);
        // 타이밍 공격 방지를 위한 상수시간 비교
        if (!MessageDigest.isEqual(expected, provided)) {
            throw ApiException.of(ErrorCode.UNAUTHORIZED, "관리자 인증에 실패했습니다.");
        }
    }
}
