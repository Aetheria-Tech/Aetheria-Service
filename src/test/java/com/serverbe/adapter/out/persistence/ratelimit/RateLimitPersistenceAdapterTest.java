package com.serverbe.adapter.out.persistence.ratelimit;

import com.serverbe.application.port.out.ratelimit.RateLimitScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("처리율 제한 Redis 어댑터")
class RateLimitPersistenceAdapterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    @SuppressWarnings("unchecked")
    private RedisScript<Boolean> rateLimitScript;

    @Mock
    private RateLimitFallbackHandler fallbackHandler;

    @InjectMocks
    private RateLimitPersistenceAdapter adapter;

    private static final String KEY = "rate:user:100:/api/v1/test";
    private static final Throwable REDIS_DOWN = new RuntimeException("Redis Down");

    @Test
    @DisplayName("Lua 스크립트에 키와 capacity·refillRate·요청토큰수·시각을 전달한다")
    void Lua_스크립트에_인자를_전달한다() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).willReturn(true);

        boolean result = adapter.isAllowed(RateLimitScope.USER, KEY, 10, 1);

        assertThat(result).isTrue();

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), args.capture());

        assertThat(keys.getValue()).containsExactly(KEY);
        assertThat(args.getAllValues().get(0)).startsWith("10", "1", "1");
    }

    @Test
    @DisplayName("Redis가 null을 돌려주면 차단으로 해석한다")
    void Redis가_null을_돌려주면_차단으로_해석한다() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).willReturn(null);

        assertThat(adapter.isAllowed(RateLimitScope.USER, KEY, 10, 1)).isFalse();
    }

    @Test
    @DisplayName("USER 스코프 폴백은 사용자 핸들러로 위임한다")
    void USER_스코프_폴백은_사용자_핸들러로_위임한다() {
        given(fallbackHandler.handleUserFallback(KEY, 10, REDIS_DOWN)).willReturn(true);

        assertThat(adapter.fallbackIsAllowed(RateLimitScope.USER, KEY, 10, 1, REDIS_DOWN)).isTrue();

        verify(fallbackHandler).handleUserFallback(KEY, 10, REDIS_DOWN);
    }

    @Test
    @DisplayName("IP 스코프 폴백은 IP 핸들러로 위임한다")
    void IP_스코프_폴백은_IP_핸들러로_위임한다() {
        String ipKey = "rate:ip:127.0.0.1:/api/v1/test";
        given(fallbackHandler.handleIpFallback(ipKey, REDIS_DOWN)).willReturn(true);

        assertThat(adapter.fallbackIsAllowed(RateLimitScope.IP, ipKey, 5, 1, REDIS_DOWN)).isTrue();

        verify(fallbackHandler).handleIpFallback(ipKey, REDIS_DOWN);
    }
}
