package com.skala.imlate.registration.service;

/**
 * 등록 취소 커맨드(컨트롤러 → 서비스).
 *
 * <p>{@link RegistrationCommand} 와 마찬가지로 정규화 전 값이며, 정규화·재검증은
 * {@link RegistrationService#cancel(CancelCommand)} 가 수행한다.
 *
 * <p><b>본인 확인은 네 값이 모두 맞아야 성립한다</b> — 반·이름·호수로 등록을 특정하고,
 * 비밀번호로 그 등록의 주인임을 증명한다. 셋만으로 취소를 허용하면 같은 반 누구나
 * 남의 등록을 지울 수 있다(운영 요청의 핵심).
 *
 * @param className   반
 * @param studentName 이름
 * @param roomNumber  기숙사 호수
 * @param password    등록 때 설정한 취소 비밀번호(숫자 4자리)
 * @param clientIp    요청 클라이언트 IP(악용 추적용)
 */
public record CancelCommand(String className, String studentName, String roomNumber,
                            String password, String clientIp) {
}
