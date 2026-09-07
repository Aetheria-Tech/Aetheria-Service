package com.serverbe.application.service;

import com.serverbe.application.config.RateLimitKeyPolicy;
import com.serverbe.application.port.out.ratelimit.RateLimitPort;
import com.serverbe.application.port.out.ratelimit.RateLimitScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 이 서비스의 책임은 <b>키 조립</b>과 <b>스코프 지정</b> 둘뿐입니다.
 * Redis 장애 시의 폴백은 어댑터로 내려갔으므로 여기서 검증하지 않습니다
 * ({@code RateLimitPersistenceAdapterTest}, {@code RateLimitFallbackHandlerTest} 참고).
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    private RateLimiterService rateLimiterService;

    @Mock
    private RateLimitPort rateLimitPort;

    private static final String TEST_ENDPOINT = "/api/v1/test";

    @BeforeEach
    void setUp() {
        // 키 접두사는 순수 record라 mocking할 것이 없습니다. application.yml의 실제 값과 동일하게 둡니다.
        rateLimiterService = new RateLimiterService(
                rateLimitPort, new RateLimitKeyPolicy("rate:user:", "rate:ip:"));
    }

    @Test
    @DisplayName("User ID 기반 요청이 허용되면 true를 반환한다 (Key 조합 및 USER 스코프 확인)")
    void isAllowedForUser_Allowed() {
        Long userId = 100L;
        String expectedKey = "rate:user:100:" + TEST_ENDPOINT;
        int capacity = 10;
        int refillRate = 1;

        given(rateLimitPort.isAllowed(RateLimitScope.USER, expectedKey, capacity, refillRate)).willReturn(true);

        boolean result = rateLimiterService.isAllowedForUser(userId, TEST_ENDPOINT, capacity, refillRate);

        assertThat(result).isTrue();
        verify(rateLimitPort).isAllowed(RateLimitScope.USER, expectedKey, capacity, refillRate);
    }

    @Test
    @DisplayName("IP 기반 요청이 차단되면 false를 반환한다 (Key 조합 및 IP 스코프 확인)")
    void isAllowedForIp_Blocked() {
        String ip = "192.168.0.1";
        String expectedKey = "rate:ip:192.168.0.1:" + TEST_ENDPOINT;
        int capacity = 5;
        int refillRate = 1;

        given(rateLimitPort.isAllowed(RateLimitScope.IP, expectedKey, capacity, refillRate)).willReturn(false);

        boolean result = rateLimiterService.isAllowedForIp(ip, TEST_ENDPOINT, capacity, refillRate);

        assertThat(result).isFalse();
        verify(rateLimitPort).isAllowed(RateLimitScope.IP, expectedKey, capacity, refillRate);
    }
}
