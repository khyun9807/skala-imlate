package com.skala.imlate.registration.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 등록 취소 요청 본문.
 *
 * <p>필드 이름을 {@link RegistrationRequest} 와 똑같이 맞춘 것은 의도한 것이다.
 * 개인 축 rate limit 키를 만드는 {@code PersonKeyResolver} 가 본문에서
 * {@code className / studentName / roomNumber} 를 읽으므로, 이름이 같아야 취소 요청도
 * 등록과 동일한 개인 버킷에 들어가 도배가 막힌다.
 *
 * @param className   반
 * @param studentName 이름
 * @param roomNumber  기숙사 호수
 * @param password    등록할 때 정한 취소 비밀번호(숫자 4자리)
 */
public record CancelRequest(

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
        String roomNumber,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Pattern(regexp = "^[0-9]{4}$", message = "비밀번호는 숫자 4자리로 입력해 주세요.")
        String password
) {
}
