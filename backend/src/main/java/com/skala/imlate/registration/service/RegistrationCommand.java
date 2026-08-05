package com.skala.imlate.registration.service;

/**
 * 등록 요청 커맨드(컨트롤러 → 서비스).
 *
 * <p>여기 담긴 값은 아직 정규화 전이다. 정규화(trim, 연속 공백 축약)와 재검증은
 * {@link RegistrationService#register(RegistrationCommand)} 가 수행한다.
 *
 * @param className   반
 * @param studentName 이름
 * @param roomNumber  기숙사 호수
 * @param clientIp    요청 클라이언트 IP(WAL 장애 추적용)
 */
public record RegistrationCommand(String className, String studentName,
                                  String roomNumber, String clientIp) {
}
