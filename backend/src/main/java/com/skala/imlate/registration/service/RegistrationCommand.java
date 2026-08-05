package com.skala.imlate.registration.service;

/**
 * 등록 요청 커맨드(컨트롤러 → 서비스).
 *
 * <p>여기 담긴 값은 아직 정규화 전이다. 정규화(trim, 연속 공백 축약)와 재검증은
 * {@link RegistrationService#register(RegistrationCommand)} 가 수행한다.
 *
 * @param className      반
 * @param studentName    이름
 * @param roomNumber     기숙사 호수
 * @param cancelPassword 나중에 취소할 때 쓸 비밀번호(숫자 4자리).
 *                       해시해서 저장하고 평문은 DB·로그·WAL 어디에도 남기지 않는다
 * @param clientIp       요청 클라이언트 IP(WAL 장애 추적용)
 */
public record RegistrationCommand(String className, String studentName, String roomNumber,
                                  String cancelPassword, String clientIp) {
}
