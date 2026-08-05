package com.skala.imlate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.skala.imlate.common.properties.ImlateProperties;
import com.skala.imlate.common.properties.NotificationProperties;
import com.skala.imlate.notification.channel.EmailSender;
import com.skala.imlate.notification.channel.SmsSender;
import com.skala.imlate.notification.service.CurfewNotificationService;
import com.skala.imlate.registration.service.ReconciliationService;
import com.skala.imlate.registration.service.RegistrationService;
import com.skala.imlate.registration.service.RegistrationWindowPolicy;
import com.skala.imlate.registration.web.LookupController;
import com.skala.imlate.registration.web.RegistrationController;
import com.skala.imlate.stats.StatsQueryService;

/**
 * 스프링 컨텍스트 로딩 테스트.
 *
 * <p>외부 인프라에 의존하지 않는다. DB 는 H2(MySQL 모드) 인메모리 + Flyway 비활성 + ddl-auto create-drop 이고,
 * Redis 는 {@link RedisConnectionFactory} 를 목으로 대체해 실제 연결을 만들지 않는다.
 * 발송 스케줄러는 {@code imlate.notification.enabled=false} + cron "-" 로 비활성이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("애플리케이션 컨텍스트")
class ImlateApplicationTests {

    /** 테스트 중 실제 Redis 에 붙지 않도록 커넥션 팩토리를 목으로 대체한다. */
    @MockitoBean
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("스프링 컨텍스트가 정상적으로 기동된다")
    void 컨텍스트가_로딩된다() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(RegistrationService.class)).isNotNull();
        assertThat(context.getBean(ReconciliationService.class)).isNotNull();
        assertThat(context.getBean(RegistrationWindowPolicy.class)).isNotNull();
        assertThat(context.getBean(CurfewNotificationService.class)).isNotNull();
        assertThat(context.getBean(StatsQueryService.class)).isNotNull();
        assertThat(context.getBean(RegistrationController.class)).isNotNull();
        assertThat(context.getBean(LookupController.class)).isNotNull();
    }

    @Test
    @DisplayName("서비스 기준 시계는 Asia/Seoul 로 초기화된다")
    void 시계는_KST_다() {
        Clock clock = context.getBean("clock", Clock.class);

        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        assertThat(context.getBean("serviceZoneId", ZoneId.class)).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    @DisplayName("등록 창·조회 설정이 테스트 프로파일 값으로 바인딩된다")
    void 설정이_바인딩된다() {
        ImlateProperties properties = context.getBean(ImlateProperties.class);

        assertThat(properties.timezone()).isEqualTo("Asia/Seoul");
        assertThat(properties.registration().closeTime().toString()).isEqualTo("22:00");
        assertThat(properties.registration().returnTime().toString()).isEqualTo("23:30");
        assertThat(properties.registration().curfewTime().toString()).isEqualTo("22:30");
        assertThat(properties.lookup().tokenSecret()).isNotBlank();
    }

    @Test
    @DisplayName("테스트 프로파일에서는 사감 발송이 비활성이고 문자/이메일은 noop 구현이 주입된다")
    void 발송은_noop_이고_비활성이다() {
        assertThat(context.getBean(NotificationProperties.class).enabled()).isFalse();
        assertThat(context.getBean(SmsSender.class).providerName()).isEqualTo("noop");
        assertThat(context.getBean(EmailSender.class).providerName()).isEqualTo("noop");
    }
}
