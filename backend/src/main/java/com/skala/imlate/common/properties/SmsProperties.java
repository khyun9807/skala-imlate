package com.skala.imlate.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 문자 발송 설정(`imlate.sms.*`). provider 값으로 구현체를 선택한다.
 *
 * @param provider "aligo" 또는 "noop"
 * @param aligo    Aligo REST 연동 설정
 */
@ConfigurationProperties(prefix = "imlate.sms")
public record SmsProperties(String provider, Aligo aligo) {

    public SmsProperties {
        if (provider == null || provider.isBlank()) {
            provider = "noop";
        }
        if (aligo == null) {
            aligo = new Aligo(null, null, null, null, true, 0, 0);
        }
    }

    /**
     * Aligo 문자 API 설정.
     *
     * @param apiUrl           발송 엔드포인트(기본 https://apis.aligo.in/send/)
     * @param apiKey           Aligo API 키
     * @param userId           Aligo 사용자 ID
     * @param sender           발신 번호(사전 등록 필요)
     * @param testMode         테스트 모드 여부(true 면 실제 발송되지 않음)
     * @param connectTimeoutMs 연결 타임아웃(ms)
     * @param readTimeoutMs    응답 대기 타임아웃(ms)
     */
    public record Aligo(String apiUrl, String apiKey, String userId,
                        String sender, boolean testMode, int connectTimeoutMs, int readTimeoutMs) {

        public Aligo {
            if (apiUrl == null || apiUrl.isBlank()) {
                apiUrl = "https://apis.aligo.in/send/";
            }
            if (apiKey == null) {
                apiKey = "";
            }
            if (userId == null) {
                userId = "";
            }
            if (sender == null) {
                sender = "";
            }
            if (connectTimeoutMs <= 0) {
                connectTimeoutMs = 3000;
            }
            if (readTimeoutMs <= 0) {
                readTimeoutMs = 5000;
            }
        }
    }
}
