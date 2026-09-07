package com.serverbe.adapter.out.persistence.ratelimit;

import com.serverbe.application.port.out.ratelimit.RateLimitPort;
import com.serverbe.application.port.out.ratelimit.RateLimitScope;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * @responsibility Redis Lua 스크립트로 토큰 버킷 판정을 원자적으로 수행합니다.
 * @implSpec 조회와 갱신을 한 번의 스크립트 실행으로 묶어야 동시 요청이 같은 잔량을 읽는 일이 없습니다.
 * @implNote 서킷 브레이커가 애플리케이션 서비스가 아니라 여기 붙어 있는 이유는, 브레이커가 지키는
 * 대상이 <b>Redis</b>이고 Redis를 호출하는 곳이 이 어댑터이기 때문입니다. 판정 실패 시의 폴백 정책은
 * {@link RateLimitFallbackHandler}에 있고, 대상 종류에 따라 다르게 동작합니다.
 */
@Slf4j
@Component
public class RateLimitPersistenceAdapter implements RateLimitPort {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Boolean> rateLimitScript;
    private final RateLimitFallbackHandler fallbackHandler;

    public RateLimitPersistenceAdapter(
            StringRedisTemplate redisTemplate,
            @Qualifier("rateLimitScript") RedisScript<Boolean> rateLimitScript,
            RateLimitFallbackHandler fallbackHandler
    ) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.fallbackHandler = fallbackHandler;
    }

    @Override
    @CircuitBreaker(name = "redisRateLimit", fallbackMethod = "fallbackIsAllowed")
    public boolean isAllowed(RateLimitScope scope, String key, int capacity, int refillRate) {
        List<String> keys = Collections.singletonList(key);

        try {
            return Boolean.TRUE.equals(redisTemplate.execute(
                    rateLimitScript,
                    keys,
                    String.valueOf(capacity),
                    String.valueOf(refillRate),
                    "1", // requested tokens (현재는 1로 고정)
                    String.valueOf(System.currentTimeMillis())
            ));
        } catch (Exception e) {
            log.error("Rate Limit Redis Error: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * @responsibility Redis 판정이 실패했을 때 대상 종류에 맞는 폴백으로 넘깁니다.
     * @implNote resilience4j가 찾을 수 있도록 <b>public</b>이고, 원본과 같은 파라미터 뒤에
     * {@link Throwable} 하나가 붙은 형태여야 합니다. 이 형태가 어긋나면 컴파일은 통과하지만
     * Redis가 죽는 첫 순간에 {@code NoSuchMethodException}으로 드러납니다.
     */
    public boolean fallbackIsAllowed(RateLimitScope scope, String key, int capacity, int refillRate, Throwable t) {
        return scope == RateLimitScope.USER
                ? fallbackHandler.handleUserFallback(key, capacity, t)
                : fallbackHandler.handleIpFallback(key, t);
    }
}
