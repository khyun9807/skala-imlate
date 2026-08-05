package com.skala.imlate.common.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@link ImlateProperties} 설정 바인딩 회귀 테스트.
 *
 * <p><b>왜 이 테스트가 있는가</b> — {@code Registration} 레코드에 축약 생성자를 하나 추가했더니
 * 스프링이 어느 생성자로 바인딩할지 정하지 못해 <u>바인딩이 조용히 실패</u>했다.
 * 그 결과 application.yml 과 환경변수를 전부 무시하고 레코드 기본값만 쓰게 되었는데,
 * 기본값이 설정값과 우연히 같아서(둘 다 21:45) 화면상으로는 멀쩡해 보였다.
 * 실제로는 {@code IMLATE_REGISTRATION_CLOSE_TIME} 같은 운영 스위치가 죽은 상태였다 —
 * 마감 시각을 바꾸려고 환경변수를 넣어도 아무 일도 일어나지 않는다.
 *
 * <p>그래서 이 테스트는 <b>기본값과 다른 값</b>을 넣고 그 값이 실제로 도착했는지 확인한다.
 * 기본값과 같은 값으로 검사하면 바인딩이 죽어도 통과해 버린다(원래 사고가 그랬다).
 */
@DisplayName("설정 바인딩(ImlateProperties)")
class ImlatePropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ImlateProperties.class)
    static class TestConfig {
    }

    @Test
    @DisplayName("등록 창 시각이 설정에서 실제로 주입된다 (기본값과 다른 값으로 확인)")
    void 등록_창_시각이_주입된다() {
        runner.withPropertyValues(
                        "imlate.registration.open-time=01:23",
                        "imlate.registration.close-time=23:59",
                        "imlate.registration.return-time=23:45",
                        "imlate.registration.curfew-time=22:15")
                .run(context -> {
                    ImlateProperties.Registration registration =
                            context.getBean(ImlateProperties.class).registration();

                    assertThat(registration.openTime()).isEqualTo(LocalTime.of(1, 23));
                    // 21:45(기본값)가 나오면 바인딩이 죽은 것이다.
                    assertThat(registration.closeTime()).isEqualTo(LocalTime.of(23, 59));
                    assertThat(registration.returnTime()).isEqualTo(LocalTime.of(23, 45));
                    assertThat(registration.curfewTime()).isEqualTo(LocalTime.of(22, 15));
                });
    }

    @Test
    @DisplayName("길이 제한도 설정에서 주입된다")
    void 길이_제한이_주입된다() {
        runner.withPropertyValues(
                        "imlate.registration.max-name-length=13",
                        "imlate.registration.max-room-length=17")
                .run(context -> {
                    ImlateProperties.Registration registration =
                            context.getBean(ImlateProperties.class).registration();

                    assertThat(registration.maxNameLength()).isEqualTo(13);
                    assertThat(registration.maxRoomLength()).isEqualTo(17);
                });
    }

    @Test
    @DisplayName("취소 정책(비밀번호 자릿수·시도 상한·해시 반복)도 설정에서 주입된다")
    void 취소_정책이_주입된다() {
        runner.withPropertyValues(
                        "imlate.registration.cancel.password-length=6",
                        "imlate.registration.cancel.max-attempts=3",
                        "imlate.registration.cancel.hash-iterations=33000")
                .run(context -> {
                    ImlateProperties.Cancel cancel =
                            context.getBean(ImlateProperties.class).registration().cancel();

                    assertThat(cancel.passwordLength()).isEqualTo(6);
                    assertThat(cancel.maxAttempts()).isEqualTo(3);
                    assertThat(cancel.hashIterations()).isEqualTo(33_000);
                });
    }

    @Test
    @DisplayName("해시 반복 횟수가 터무니없이 작으면 하한으로 올린다(설정 실수 방어)")
    void 해시_반복_하한이_적용된다() {
        runner.withPropertyValues("imlate.registration.cancel.hash-iterations=5")
                .run(context -> assertThat(context.getBean(ImlateProperties.class)
                        .registration().cancel().hashIterations())
                        .isEqualTo(ImlateProperties.Cancel.MIN_HASH_ITERATIONS));
    }

    @Test
    @DisplayName("설정이 없으면 SPEC 기본값으로 채워진다")
    void 미설정이면_기본값이다() {
        runner.run(context -> {
            ImlateProperties properties = context.getBean(ImlateProperties.class);
            ImlateProperties.Registration registration = properties.registration();

            assertThat(properties.timezone()).isEqualTo(ImlateProperties.DEFAULT_TIMEZONE);
            assertThat(registration.openTime()).isEqualTo(LocalTime.MIDNIGHT);
            assertThat(registration.closeTime())
                    .isEqualTo(ImlateProperties.Registration.DEFAULT_CLOSE_TIME);
            assertThat(registration.cancel().passwordLength())
                    .isEqualTo(ImlateProperties.Cancel.DEFAULT_PASSWORD_LENGTH);
            assertThat(registration.cancel().maxAttempts())
                    .isEqualTo(ImlateProperties.Cancel.DEFAULT_MAX_ATTEMPTS);
        });
    }

    @Test
    @DisplayName("조회·관리자 설정도 함께 주입된다")
    void 조회와_관리자_설정도_주입된다() {
        runner.withPropertyValues(
                        "imlate.lookup.base-url=https://example.test",
                        "imlate.lookup.token-ttl-hours=7",
                        "imlate.admin.api-key=binding-check",
                        "imlate.wal.key-prefix=imlate:check:wal",
                        "imlate.wal.ttl-days=3")
                .run(context -> {
                    ImlateProperties properties = context.getBean(ImlateProperties.class);

                    assertThat(properties.lookup().baseUrl()).isEqualTo("https://example.test");
                    assertThat(properties.lookup().tokenTtlHours()).isEqualTo(7);
                    assertThat(properties.admin().apiKey()).isEqualTo("binding-check");
                    assertThat(properties.wal().keyPrefix()).isEqualTo("imlate:check:wal");
                    assertThat(properties.wal().ttlDays()).isEqualTo(3);
                });
    }
}
