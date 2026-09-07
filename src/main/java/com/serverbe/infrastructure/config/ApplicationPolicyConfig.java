package com.serverbe.infrastructure.config;

import com.serverbe.application.config.ArtSearchPolicy;
import com.serverbe.application.config.RateLimitKeyPolicy;
import com.serverbe.application.config.SessionPolicy;
import com.serverbe.application.config.TaskTimeoutPolicy;
import com.serverbe.infrastructure.config.properties.ArtProperties;
import com.serverbe.infrastructure.config.properties.JwtProperties;
import com.serverbe.infrastructure.config.properties.RateLimitProperties;
import com.serverbe.infrastructure.config.properties.TaskProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @responsibility 스프링 {@code @ConfigurationProperties} 바인딩 결과를 애플리케이션 계층이 아는
 * 순수 레코드로 옮겨 담습니다.
 * @implSpec 애플리케이션 서비스는 {@code infrastructure.config.properties.*}를 직접 참조하지 않습니다.
 * 그 번역을 담당하는 유일한 지점이 이 클래스입니다.
 * @implNote {@code @Value}로 서비스에 값을 직접 주입하지 않은 이유가 있습니다.
 * {@code jwt.refresh-token.expiration-days: 60d}가 {@link java.time.Duration}으로 바인딩되는 것은
 * {@code @ConfigurationProperties}의 완화된 바인딩 덕분이고, {@code @Value} 경로는 같은 변환이
 * 적용된다는 보장이 없습니다. 잘못된 TTL이 조용히 들어가는 쪽이 간접 계층 하나보다 나쁩니다.
 */
@Configuration
public class ApplicationPolicyConfig {

    @Bean
    public ArtSearchPolicy artSearchPolicy(ArtProperties properties) {
        return new ArtSearchPolicy(properties.maxRadius(), properties.maxResultLimit());
    }

    @Bean
    public TaskTimeoutPolicy taskTimeoutPolicy(TaskProperties properties) {
        return new TaskTimeoutPolicy(properties.taskTimeoutThresholdMinutes());
    }

    @Bean
    public RateLimitKeyPolicy rateLimitKeyPolicy(RateLimitProperties properties) {
        return new RateLimitKeyPolicy(properties.prefix().user(), properties.prefix().ip());
    }

    @Bean
    public SessionPolicy sessionPolicy(JwtProperties properties) {
        return new SessionPolicy(properties.refreshToken().expirationDays());
    }
}
