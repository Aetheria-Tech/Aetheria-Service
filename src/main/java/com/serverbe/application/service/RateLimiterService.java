package com.serverbe.application.service;

import com.serverbe.application.config.RateLimitKeyPolicy;
import com.serverbe.application.port.in.ratelimit.RateLimitUseCase;
import com.serverbe.application.port.out.ratelimit.RateLimitPort;
import com.serverbe.application.port.out.ratelimit.RateLimitScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @responsibility 처리율 제한 버킷의 <b>키를 조립</b>하고 판정을 포트에 위임합니다.
 * @implSpec 키 이름 규칙(사용자/IP 접두사 + 식별자 + 엔드포인트)은 애플리케이션 정책이므로 여기 남습니다.
 * @implNote 저장소 장애 대응(서킷 브레이커·폴백)은 이 클래스에 없습니다. 서킷 브레이커가 지키는 대상은
 * Redis이고 Redis를 호출하는 곳은 어댑터이므로,
 * {@code adapter.out.persistence.ratelimit.RateLimitPersistenceAdapter}가 그 책임을 집니다.
 */
@Slf4j
@Service
public class RateLimiterService implements RateLimitUseCase {

    private final RateLimitPort rateLimitPort;
    private final String rateLimitUserPrefix;
    private final String rateLimitIpPrefix;

    public RateLimiterService(RateLimitPort rateLimitPort, RateLimitKeyPolicy rateLimitKeyPolicy) {
        this.rateLimitPort = rateLimitPort;
        this.rateLimitUserPrefix = rateLimitKeyPolicy.userPrefix();
        this.rateLimitIpPrefix = rateLimitKeyPolicy.ipPrefix();
    }

    @Override
    public boolean isAllowedForUser(Long userId, String endpoint, int capacity, int refillRate) {
        String key = rateLimitUserPrefix + userId + ":" + endpoint;
        return rateLimitPort.isAllowed(RateLimitScope.USER, key, capacity, refillRate);
    }

    @Override
    public boolean isAllowedForIp(String ip, String endpoint, int ipCapacity, int ipRefillRate) {
        String key = rateLimitIpPrefix + ip + ":" + endpoint;
        return rateLimitPort.isAllowed(RateLimitScope.IP, key, ipCapacity, ipRefillRate);
    }
}
