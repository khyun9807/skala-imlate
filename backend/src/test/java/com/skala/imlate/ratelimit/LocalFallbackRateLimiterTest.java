package com.skala.imlate.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.skala.imlate.common.properties.RateLimitProperties;

/**
 * {@link LocalFallbackRateLimiter} 단위 테스트.
 *
 * <p>Redis 장애 시 사용되는 1분 고정 윈도우 카운터다. 시각은 인자로 주입되므로 실제 대기 없이
 * 윈도우 경과를 검증할 수 있다.
 */
@DisplayName("로컬 폴백 rate limiter(LocalFallbackRateLimiter)")
class LocalFallbackRateLimiterTest {

    /** 분 경계에 정확히 맞는 기준 시각(epoch milli). */
    private static final long WINDOW_START = 1_800_000_000_000L;
    private static final long ONE_MINUTE = 60_000L;

    private static RateLimitProperties properties(int fallbackPermitsPerMinute) {
        return new RateLimitProperties(true, true,
                new RateLimitProperties.Rule(120, 120, 60),
                new RateLimitProperties.Rule(3, 3, 60),
                new RateLimitProperties.Rule(40, 40, 60),
                List.of(), fallbackPermitsPerMinute);
    }

    @Test
    @DisplayName("한도(3회) 안에서는 허용되고 남은 토큰이 하나씩 줄어든다")
    void 한도_안에서는_허용된다() {
        RateLimitProperties properties = properties(120);
        LocalFallbackRateLimiter limiter = new LocalFallbackRateLimiter(properties);
        RateLimitProperties.Rule rule = properties.register();

        RateLimitDecision first = limiter.tryConsume("k", rule, WINDOW_START);
        RateLimitDecision second = limiter.tryConsume("k", rule, WINDOW_START + 10);
        RateLimitDecision third = limiter.tryConsume("k", rule, WINDOW_START + 20);

        assertThat(first.allowed()).isTrue();
        assertThat(first.limit()).isEqualTo(3L);
        assertThat(first.remaining()).isEqualTo(2L);
        assertThat(second.remaining()).isEqualTo(1L);
        assertThat(third.remaining()).isZero();
        assertThat(third.allowed()).isTrue();
    }

    @Test
    @DisplayName("한도를 초과하면 차단되고 Retry-After 가 1초 이상으로 계산된다")
    void 한도_초과시_차단된다() {
        RateLimitProperties properties = properties(120);
        LocalFallbackRateLimiter limiter = new LocalFallbackRateLimiter(properties);
        RateLimitProperties.Rule rule = properties.register();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume("k", rule, WINDOW_START + i).allowed()).isTrue();
        }

        RateLimitDecision blocked = limiter.tryConsume("k", rule, WINDOW_START + 100);

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.remaining()).isZero();
        assertThat(blocked.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
        assertThat(blocked.resetEpochSeconds(WINDOW_START + 100))
                .isEqualTo((WINDOW_START + ONE_MINUTE) / 1000L);
    }

    @Test
    @DisplayName("윈도우(1분)가 지나면 다시 허용된다")
    void 윈도우_경과_후_회복된다() {
        RateLimitProperties properties = properties(120);
        LocalFallbackRateLimiter limiter = new LocalFallbackRateLimiter(properties);
        RateLimitProperties.Rule rule = properties.register();

        for (int i = 0; i < 3; i++) {
            limiter.tryConsume("k", rule, WINDOW_START + i);
        }
        assertThat(limiter.tryConsume("k", rule, WINDOW_START + 100).allowed()).isFalse();

        RateLimitDecision afterWindow = limiter.tryConsume("k", rule, WINDOW_START + ONE_MINUTE);

        assertThat(afterWindow.allowed()).isTrue();
        assertThat(afterWindow.remaining()).isEqualTo(2L);
    }

    @Test
    @DisplayName("키(클라이언트)마다 카운터가 독립적으로 관리된다")
    void 키별로_독립적으로_계산된다() {
        RateLimitProperties properties = properties(120);
        LocalFallbackRateLimiter limiter = new LocalFallbackRateLimiter(properties);
        RateLimitProperties.Rule rule = properties.register();

        for (int i = 0; i < 3; i++) {
            limiter.tryConsume("imlate:rl:register:1.1.1.1", rule, WINDOW_START + i);
        }

        assertThat(limiter.tryConsume("imlate:rl:register:1.1.1.1", rule, WINDOW_START + 10).allowed()).isFalse();
        assertThat(limiter.tryConsume("imlate:rl:register:2.2.2.2", rule, WINDOW_START + 10).allowed()).isTrue();
        assertThat(limiter.trackedKeyCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("폴백 상한(local-fallback-permits-per-minute)이 규칙보다 작으면 상한이 적용된다")
    void 폴백_상한이_적용된다() {
        RateLimitProperties properties = properties(2);
        LocalFallbackRateLimiter limiter = new LocalFallbackRateLimiter(properties);
        RateLimitProperties.Rule global = properties.global();

        assertThat(limiter.tryConsume("g", global, WINDOW_START).allowed()).isTrue();
        assertThat(limiter.tryConsume("g", global, WINDOW_START + 1).allowed()).isTrue();
        assertThat(limiter.tryConsume("g", global, WINDOW_START + 2).allowed()).isFalse();
    }

    @Test
    @DisplayName("리미터 이름은 'local' 이다")
    void 리미터_이름() {
        assertThat(new LocalFallbackRateLimiter(properties(120)).name()).isEqualTo("local");
    }
}
