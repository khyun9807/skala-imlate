package com.skala.imlate.registration.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 등록 요청 본문(SPEC §5.5).
 *
 * <p>제어문자·특수문자를 막기 위해 한글·영문·숫자와 공백, 괄호, 하이픈만 허용한다.
 * 정규식은 {@code ^[가-힣A-Za-z0-9 ()\-]{1,20}$} 이며, 서비스에서 정규화(trim + 연속 공백 축약) 후
 * 같은 문자 집합으로 한 번 더 검증한다.
 *
 * @param className   반 (예: "1반")
 * @param studentName 이름 (예: "홍길동")
 * @param roomNumber  기숙사 호수 (예: "302")
 */
public record RegistrationRequest(

        @NotBlank(message = "반을 입력해 주세요.")
        @Size(max = 20, message = "반은 20자 이내로 입력해 주세요.")
        @Pattern(regexp = "^[가-힣A-Za-z0-9 ()\\-]{1,20}$",
                message = "반에는 한글·영문·숫자와 공백, 괄호, 하이픈만 사용할 수 있습니다.")
        String className,

        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(max = 20, message = "이름은 20자 이내로 입력해 주세요.")
        @Pattern(regexp = "^[가-힣A-Za-z0-9 ()\\-]{1,20}$",
                message = "이름에는 한글·영문·숫자와 공백, 괄호, 하이픈만 사용할 수 있습니다.")
        String studentName,

        @NotBlank(message = "기숙사 호수를 입력해 주세요.")
        @Size(max = 20, message = "기숙사 호수는 20자 이내로 입력해 주세요.")
        @Pattern(regexp = "^[가-힣A-Za-z0-9 ()\\-]{1,20}$",
                message = "기숙사 호수에는 한글·영문·숫자와 공백, 괄호, 하이픈만 사용할 수 있습니다.")
        String roomNumber
) {
}
