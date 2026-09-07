package com.serverbe.adapter.out.persistence.task;

import com.serverbe.application.port.out.task.TaskRateLimitPort;
import com.serverbe.domain.exception.ai.AiErrorCode;
import com.serverbe.domain.exception.ai.AiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiTaskRedisAdapter implements TaskRateLimitPort {

    private final StringRedisTemplate redisTemplate;
    private static final String LOCK_KEY_PREFIX = "ai:task:lock:";

    /**
     * 사용자별 연타 방지 락을 획득합니다.
     * <p>
     * <b>Redis 장애 시 정책 (Fail-Closed):</b><br>
     * 일반적인 Rate Limit은 가용성을 위해 장애 시 요청을 통과시키는 편이지만(Fail-Open),
     * 이 락은 <b>호출 1건당 과금되는 SageMaker 추론 비용</b>을 막는 것이 목적입니다.
     * 방어막이 사라진 채로 요청을 통과시키면 장애 시간 동안 비용이 무제한으로 발생할 수 있으므로,
     * 의도적으로 요청을 차단합니다. 다만 raw 인프라 예외가 그대로 500으로 새어 나가지 않도록
     * 클라이언트가 이해할 수 있는 비즈니스 예외로 변환합니다.
     * </p>
     * <p>
     * 반대편 정책은 {@link com.serverbe.adapter.out.persistence.ratelimit.RateLimitFallbackHandler}에
     * 있습니다. 그쪽 HTTP 처리율 제한은 Fail-Open입니다. 두 정책은 <b>의도적으로</b> 반대이며,
     * 일관성을 이유로 하나로 합쳐서는 안 됩니다.
     * </p>
     *
     * @param userId  사용자 ID
     * @param seconds 락 유지 시간(초)
     * @return 락 획득 성공 시 true, 이미 다른 요청이 점유 중이면 false
     * @throws AiException Redis 통신 자체가 실패한 경우
     */
    @Override
    public boolean tryLock(Long userId, int seconds) {
        String key = LOCK_KEY_PREFIX + userId;

        try {
            // SETNX + EXPIRE를 원자적으로 실행
            Boolean success = redisTemplate.opsForValue()
                    .setIfAbsent(key, "locked", Duration.ofSeconds(seconds));

            return Boolean.TRUE.equals(success);

        } catch (DataAccessException e) {
            log.error("[Rate Limit] Redis 장애로 AI 작업 락을 획득할 수 없습니다. 비용 보호를 위해 요청을 차단합니다 - UserID: {}", userId, e);
            throw new AiException(AiErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }
}
