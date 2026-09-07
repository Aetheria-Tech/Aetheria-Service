package com.serverbe.adapter.out.persistence.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @responsibility Redis가 응답하지 않을 때 처리율 제한을 로컬에서 판정합니다.
 * @implSpec 정책은 대상에 따라 다릅니다. 사용자 요청은 로컬 캐시로 세어 <b>한도 안에서만</b> 허용하고,
 * IP 요청은 <b>무조건 허용</b>합니다. IP 버킷은 원래 광범위한 남용만 막는 용도라 장애 중에 막아 봐야
 * 정상 사용자만 다칩니다.
 * @implNote 두 정책 모두 fail-open 계열이라는 점이 중요합니다. 반대로
 * {@code adapter.out.persistence.task.AiTaskRedisAdapter#tryLock}은 fail-closed입니다 —
 * 그쪽은 막지 못하면 SageMaker 추론 비용이 그대로 청구되기 때문입니다. 두 정책은 의도적으로 다르며
 * 하나로 통일해서는 안 됩니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFallbackHandler {

    private final Cache<String, Integer> localRateLimitCache;

    /**
     * @param key      이미 조립된 버킷 키
     * @param capacity 로컬에서 허용할 최대 요청 수
     * @param t        서킷 브레이커가 잡은 원인 예외
     * @return 한도 이내면 true, 초과하면 false
     */
    public boolean handleUserFallback(String key, int capacity, Throwable t) {
        Integer currentCount = localRateLimitCache.asMap().compute(key, (k, count) -> {
            if (count == null) return 1;
            return count + 1;
        });

        if (currentCount > capacity) {
            log.warn("[L1 Local 방어막] 한도 초과 차단! Key: {}, 현재 요청 수: {}", key, currentCount);
            return false;
        }
        log.info("[Circuit Breaker] Redis 장애! 로컬 캐시로 요청 허용 ({} / {}) - 사유: {}", currentCount, capacity, t.getMessage());
        return true;
    }

    /**
     * @param key 이미 조립된 버킷 키
     * @param t   서킷 브레이커가 잡은 원인 예외
     * @return 항상 true (Fail-Open)
     */
    public boolean handleIpFallback(String key, Throwable t) {
        log.error("[Circuit Breaker] Redis 장애 감지! {} 의 요청을 무조건 허용(Fail-Open). 사유: {}", key, t.getMessage());
        return true;
    }
}
