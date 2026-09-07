package com.serverbe.adapter.out.persistence.task;

import com.serverbe.domain.exception.ai.AiErrorCode;
import com.serverbe.domain.exception.ai.AiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * @responsibility SageMaker 비용 락의 <b>fail-closed</b> 정책을 고정합니다.
 * @implNote {@code RateLimitFallbackHandlerTest}가 고정하는 HTTP 처리율 제한은 fail-open입니다.
 * 두 정책은 의도적으로 반대이며, 여기를 fail-open으로 바꾸면 Redis 장애 시간 동안 추론 비용이
 * 무제한으로 발생합니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 작업 중복 방지 락")
class AiTaskRedisAdapterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private AiTaskRedisAdapter adapter;

    @Test
    @DisplayName("락을 처음 잡으면 true를 반환한다")
    void 락을_처음_잡으면_true를_반환한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(eq("ai:task:lock:1"), anyString(), any(Duration.class)))
                .willReturn(true);

        assertThat(adapter.tryLock(1L, 5)).isTrue();
    }

    @Test
    @DisplayName("이미 점유 중이면 false를 반환한다")
    void 이미_점유중이면_false를_반환한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(false);

        assertThat(adapter.tryLock(1L, 5)).isFalse();
    }

    @Test
    @DisplayName("Redis 장애 시 비용 보호를 위해 요청을 차단한다 (Fail-Closed)")
    void Redis_장애시_비용_보호를_위해_요청을_차단한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willThrow(new QueryTimeoutException("Redis Down"));

        assertThatThrownBy(() -> adapter.tryLock(1L, 5))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("errorCode", AiErrorCode.RATE_LIMIT_EXCEEDED);
    }
}
