package com.serverbe.infrastructure.config;

import com.serverbe.application.config.ArtSearchPolicy;
import com.serverbe.application.config.RateLimitKeyPolicy;
import com.serverbe.application.config.SessionPolicy;
import com.serverbe.application.config.TaskTimeoutPolicy;
import com.serverbe.infrastructure.config.properties.ArtProperties;
import com.serverbe.infrastructure.config.properties.JwtProperties;
import com.serverbe.infrastructure.config.properties.RateLimitProperties;
import com.serverbe.infrastructure.config.properties.TaskProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @responsibility 인프라 프로퍼티가 애플리케이션 정책 레코드로 손실 없이 옮겨지는지 확인합니다.
 * @implNote 특히 사용자 접두사와 IP 접두사처럼 <b>타입이 같아 뒤바뀌어도 컴파일이 되는</b> 값들을
 * 지켜봅니다. 뒤바뀌면 두 버킷이 서로의 한도를 쓰게 되지만 아무 에러도 나지 않습니다.
 */
@DisplayName("애플리케이션 정책 변환")
class ApplicationPolicyConfigTest {

    private final ApplicationPolicyConfig config = new ApplicationPolicyConfig();

    @Test
    @DisplayName("주변 검색 한도를 그대로 옮긴다")
    void 주변_검색_한도를_그대로_옮긴다() {
        ArtSearchPolicy policy = config.artSearchPolicy(new ArtProperties(5000.0, 5));

        assertThat(policy).isEqualTo(new ArtSearchPolicy(5000.0, 5));
    }

    @Test
    @DisplayName("좀비 작업 판단 기준 시간을 그대로 옮긴다")
    void 좀비_작업_판단_기준_시간을_그대로_옮긴다() {
        TaskTimeoutPolicy policy = config.taskTimeoutPolicy(new TaskProperties(10));

        assertThat(policy.timeoutThresholdMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("사용자·IP 접두사가 뒤바뀌지 않는다")
    void 사용자_IP_접두사가_뒤바뀌지_않는다() {
        RateLimitKeyPolicy policy = config.rateLimitKeyPolicy(new RateLimitProperties(
                null, null, new RateLimitProperties.Prefix("rate:user:", "rate:ip:")));

        assertThat(policy.userPrefix()).isEqualTo("rate:user:");
        assertThat(policy.ipPrefix()).isEqualTo("rate:ip:");
    }

    @Test
    @DisplayName("리프레시 토큰 수명을 그대로 옮긴다")
    void 리프레시_토큰_수명을_그대로_옮긴다() {
        SessionPolicy policy = config.sessionPolicy(new JwtProperties(
                null, null, new JwtProperties.RefreshToken(null, Duration.ofDays(60), 0), null, null, 0));

        assertThat(policy.refreshTokenTtl()).isEqualTo(Duration.ofDays(60));
    }
}
