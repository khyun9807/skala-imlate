package com.skala.imlate.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이메일 발송 설정(`imlate.email.*`). provider 값으로 구현체를 선택한다.
 *
 * @param provider "ses" 또는 "noop"
 * @param ses      Amazon SES v2 연동 설정
 */
@ConfigurationProperties(prefix = "imlate.email")
public record EmailProperties(String provider, Ses ses) {

    public EmailProperties {
        if (provider == null || provider.isBlank()) {
            provider = "noop";
        }
        if (ses == null) {
            ses = new Ses(null, null, null, null);
        }
    }

    /**
     * Amazon SES v2 설정.
     *
     * @param region           SES 리전(예: ap-northeast-2)
     * @param from             발신 이메일 주소(검증된 identity)
     * @param fromName         발신자 표시 이름
     * @param configurationSet SES configuration set 이름(선택)
     */
    public record Ses(String region, String from, String fromName, String configurationSet) {

        public Ses {
            if (region == null || region.isBlank()) {
                region = "ap-northeast-2";
            }
            if (from == null) {
                from = "";
            }
            if (fromName == null || fromName.isBlank()) {
                fromName = "기숙사 야간복귀 시스템";
            }
            if (configurationSet == null) {
                configurationSet = "";
            }
        }
    }
}
