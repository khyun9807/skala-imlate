package com.skala.imlate.registration.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 등록 요청 본문(SPEC §5.5).
 *
 * <p><b>반·기숙사 호수는 숫자만, 이름은 글자만(한글·영문)</b> 받는다(운영 요청).
 * 서비스가 정규화(trim + 연속 공백 축약)한 뒤 같은 규칙으로 한 번 더 검증한다.
 *
 * <p>이름에만 글자 사이 공백을 허용한다 — {@code "Alice Kim"} 처럼 띄어 쓰는 이름이 있다.
 *
 * <p><b>여기의 정규식이 앞뒤 공백을 눈감아 주는 것은 의도한 것이다.</b>
 * 이 계층은 <u>1차 필터</u>일 뿐이고 최종 판정은 서비스가 정규화한 뒤에 한다.
 * 여기서 공백까지 막아 버리면 {@code " 302 "} 같은 입력이 정규화될 기회도 없이 400 으로 잘려,
 * "앞뒤 공백은 알아서 정리해 준다"는 기존 동작이 조용히 사라진다.
 * 연속 공백을 한 칸으로 줄이는 일도 서비스가 한다.
 *
 * @param className      반 (예: "1")
 * @param studentName    이름 (예: "홍길동")
 * @param roomNumber     기숙사 호수 (예: "302")
 * @param cancelPassword 나중에 취소할 때 쓸 비밀번호(숫자 4자리)
 */
public record RegistrationRequest(

        @NotBlank(message = "반을 입력해 주세요.")
        @Size(max = 20, message = "반은 20자 이내로 입력해 주세요.")
        @Pattern(regexp = "^\\s*[0-9]{1,20}\\s*$", message = "반은 숫자만 입력해 주세요.")
        String className,

        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(max = 20, message = "이름은 20자 이내로 입력해 주세요.")
        @Pattern(regexp = "^\\s*[가-힣A-Za-z]+(\\s+[가-힣A-Za-z]+)*\\s*$",
                message = "이름에는 한글·영문만 사용할 수 있습니다.")
        String studentName,

        @NotBlank(message = "기숙사 호수를 입력해 주세요.")
        @Size(max = 20, message = "기숙사 호수는 20자 이내로 입력해 주세요.")
        @Pattern(regexp = "^\\s*[0-9]{1,20}\\s*$", message = "기숙사 호수는 숫자만 입력해 주세요.")
        String roomNumber,

        /*
         * 취소 비밀번호. 나중에 본인이 등록을 취소할 때 쓴다.
         *
         * 자릿수(4)를 여기에 상수로 박은 이유 — 애너테이션 값은 컴파일 타임 상수여야 해서
         * 설정값(imlate.registration.cancel.password-length)을 참조할 수 없다.
         * 설정으로 자릿수를 바꾸면 서비스 검증(RegistrationService.validatePassword)이 실제 기준이 되고,
         * 여기 400 은 그 앞단의 빠른 거절일 뿐이라 동작은 어긋나지 않는다.
         */
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Pattern(regexp = "^[0-9]{4}$", message = "비밀번호는 숫자 4자리로 입력해 주세요.")
        String cancelPassword
) {
}
