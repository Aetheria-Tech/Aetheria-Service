package com.serverbe.adapter.out.persistence.ratelimit;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @responsibility Redis 장애 시의 처리율 제한 정책을 <b>실행 가능한 형태로</b> 못 박습니다.
 * @implNote 사용자 요청은 한도 안에서만 허용(fail-open + 상한), IP 요청은 무조건 허용(fail-open).
 * 반대편인 {@code AiTaskRedisAdapterTest}는 fail-closed를 고정합니다. 두 테스트가 함께 있어야
 * 나중에 누군가 "정책이 일관되지 않다"며 하나로 합치려 할 때 그것이 의도된 비대칭임이 드러납니다.
 */
@DisplayName("처리율 제한 폴백 (Redis 장애 시)")
class RateLimitFallbackHandlerTest {

    private RateLimitFallbackHandler handler;

    private static final Throwable REDIS_DOWN = new RuntimeException("Redis Down");
    private static final String USER_KEY = "rate:user:100:/api/v1/test";
    private static final String IP_KEY = "rate:ip:127.0.0.1:/api/v1/test";

    @BeforeEach
    void setUp() {
        handler = new RateLimitFallbackHandler(
                Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).build());
    }

    @Test
    @DisplayName("사용자 요청은 한도 이내면 로컬 캐시로 세어 허용한다")
    void 사용자_요청은_한도_이내면_허용한다() {
        assertThat(handler.handleUserFallback(USER_KEY, 3, REDIS_DOWN)).isTrue();
        assertThat(handler.handleUserFallback(USER_KEY, 3, REDIS_DOWN)).isTrue();
        assertThat(handler.handleUserFallback(USER_KEY, 3, REDIS_DOWN)).isTrue();
    }

    @Test
    @DisplayName("사용자 요청이 한도를 넘으면 장애 중이라도 차단한다")
    void 사용자_요청이_한도를_넘으면_차단한다() {
        for (int i = 0; i < 3; i++) {
            handler.handleUserFallback(USER_KEY, 3, REDIS_DOWN);
        }

        assertThat(handler.handleUserFallback(USER_KEY, 3, REDIS_DOWN)).isFalse();
    }

    @Test
    @DisplayName("키가 다르면 카운트가 섞이지 않는다")
    void 키가_다르면_카운트가_섞이지_않는다() {
        handler.handleUserFallback(USER_KEY, 1, REDIS_DOWN);

        assertThat(handler.handleUserFallback("rate:user:200:/api/v1/test", 1, REDIS_DOWN)).isTrue();
    }

    @Test
    @DisplayName("IP 요청은 장애 중 상한 없이 무조건 허용한다 (Fail-Open)")
    void IP_요청은_장애중_무조건_허용한다() {
        for (int i = 0; i < 100; i++) {
            assertThat(handler.handleIpFallback(IP_KEY, REDIS_DOWN)).isTrue();
        }
    }
}
