package com.skala.imlate.notification.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.skala.imlate.common.properties.NotificationProperties;
import com.skala.imlate.support.TestFixtures;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * {@link LogDispatchHeartbeatPublisher} 단위 테스트.
 *
 * <p>이 로그 한 줄의 <b>형식이 곧 인프라 계약</b>이다. CloudWatch 로그 지표 필터가
 * {@code IMLATE_HEARTBEAT} / {@code IMLATE_DISPATCH_FAILURE} 표식을 찾아 지표로 바꾸므로,
 * 표식이나 필드 이름이 바뀌면 알람이 조용히 죽는다. 그래서 형식을 테스트로 못 박는다.
 */
@DisplayName("발송 하트비트 로그(LogDispatchHeartbeatPublisher)")
class LogDispatchHeartbeatPublisherTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        org.slf4j.Logger bound = LoggerFactory.getLogger(LogDispatchHeartbeatPublisher.class);
        // 로그 구현이 logback 이 아니면(다른 바인딩이 끼어든 환경) 형식 검증은 의미가 없어 건너뛴다.
        Assumptions.assumeTrue(bound instanceof Logger, "logback-classic 바인딩이 아니라 건너뜁니다.");
        logger = (Logger) bound;
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
        }
    }

    private LogDispatchHeartbeatPublisher publisher() {
        NotificationProperties properties = new NotificationProperties(
                true, "-", "-", 1, 60L, "SKALA 운영진", "contact@example.com", List.of(), null,
                new NotificationProperties.Heartbeat(true, "log", "Imlate", "prod"));
        return new LogDispatchHeartbeatPublisher(properties);
    }

    private List<String> loggedLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("발송을 끝내면 네임스페이스·지표명·Environment 를 담은 하트비트 한 줄을 남긴다")
    void 하트비트_한_줄을_남긴다() {
        publisher().publish(new DispatchHeartbeat(DispatchHeartbeat.Kind.DISPATCH,
                TestFixtures.DATE, "SENT", 3, List.of()));

        assertThat(loggedLines()).hasSize(1);
        assertThat(loggedLines().get(0))
                // 지표 필터가 이 문구를 통째로 찾는다. 순서가 바뀌면 알람이 조용히 죽는다.
                .startsWith("IMLATE_HEARTBEAT kind=DISPATCH")
                .contains("namespace=Imlate")
                .contains("metric=DispatchCompleted value=1")
                .contains("Environment=prod")
                .contains("kind=DISPATCH")
                .contains("date=2026-08-05")
                .contains("result=SENT")
                .contains("target=3")
                .contains("failures=0");
    }

    @Test
    @DisplayName("실패 1건당 한 줄씩 남긴다(지표 필터 합계가 곧 실패 건수가 된다)")
    void 실패는_건당_한_줄을_남긴다() {
        List<ChannelFailure> failures = List.of(
                new ChannelFailure("SMS", "사감A", "010****2222", "Aligo 발송 실패(result_code=-101)"),
                new ChannelFailure("EMAIL", "사감B", "b***@example.com", "SES 거부"));

        publisher().publish(new DispatchHeartbeat(DispatchHeartbeat.Kind.DISPATCH,
                TestFixtures.DATE, "SENT", 3, failures));

        List<String> lines = loggedLines();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("failures=2");
        assertThat(lines.get(1))
                .startsWith("IMLATE_DISPATCH_FAILURE")
                .contains("metric=DispatchFailures value=1")
                .contains("channel=SMS")
                .contains("to=010****2222");
        assertThat(lines.get(2)).contains("channel=EMAIL");
    }

    @Test
    @DisplayName("실패 사유에 줄바꿈이 섞여도 한 줄로 눕혀서 남긴다(지표 필터가 줄 단위로 읽는다)")
    void 사유를_한_줄로_눕힌다() {
        ChannelFailure multiline = new ChannelFailure("EMAIL", "사감A", "a***@example.com",
                "첫 줄\n둘째 줄\r\n셋째 줄");

        publisher().publish(new DispatchHeartbeat(DispatchHeartbeat.Kind.DISPATCH,
                TestFixtures.DATE, "SENT", 1, List.of(multiline)));

        String failureLine = loggedLines().get(1);
        assertThat(failureLine).doesNotContain("\n").doesNotContain("\r");
        assertThat(failureLine).contains("첫 줄 둘째 줄 셋째 줄");
    }

    @Test
    @DisplayName("건너뛴 경우에도 하트비트를 남긴다(신호가 아예 없는 것과 구분되어야 한다)")
    void 건너뛴_경우도_남긴다() {
        publisher().publish(new DispatchHeartbeat(DispatchHeartbeat.Kind.RETRY,
                TestFixtures.DATE, "SKIPPED:NO_FAILURE", 0, List.of()));

        assertThat(loggedLines().get(0))
                .startsWith("IMLATE_HEARTBEAT kind=RETRY")
                .contains("result=SKIPPED:NO_FAILURE");
    }

    @Test
    @DisplayName("null 하트비트를 받아도 예외를 던지지 않는다")
    void null_이면_아무것도_하지_않는다() {
        assertThatCode(() -> publisher().publish(null)).doesNotThrowAnyException();
        assertThat(loggedLines()).isEmpty();
    }
}
