package com.serverbe.adapter.out.persistence.token;

import com.serverbe.application.port.out.token.TokenBlacklistPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * @author Duskafka
 * @responsibility Redis의 TTL을 이용해 죽은 토큰을 만료 시각까지 붙잡아 둡니다.
 * @implSpec 키는 토큰 원문이 아니라 <b>SHA-256 해시</b>입니다. ({@code BL:AT:{hash}} · {@code BL:RT:{hash}})
 * 저장하는 값은 {@code "logout"}과 {@code "used"}로 나뉘어, 덤프를 봤을 때 무엇이 로그아웃이고
 * 무엇이 회전으로 죽은 토큰인지 구분됩니다.
 * @implNote 조회는 <b>fail-open</b>입니다. Redis 장애 시 예외를 던지면 그 시간 동안 모든 인증이
 * 막히므로, 차단하지 않고 감사 로그를 남깁니다. {@code AiTaskRedisAdapter}의 비용 락은 반대로
 * fail-closed이며, 두 정책이 다른 것은 의도된 것입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBlacklistAdapter implements TokenBlacklistPort {

    private final StringRedisTemplate redisTemplate;
    private final TokenRedisKeys keys;

    /**
     * @param accessToken   블랙리스트에 등록할 액세스 토큰
     * @param remainingTime 액세스 토큰의 남은 시간
     * @responsibility 액세스 토큰을 남은 수명만큼만 블랙리스트에 올립니다.
     */
    @Override
    public void blacklistAccessToken(String accessToken, Duration remainingTime) {
        redisTemplate.opsForValue().set(keys.accessTokenBlacklist(accessToken), "logout", remainingTime);
    }

    /**
     * @param refreshToken  블랙리스트에 등록할 리프레시 토큰
     * @param remainingTime 리프레시 토큰의 남은 시간
     * @responsibility 리프레시 토큰을 남은 수명만큼만 블랙리스트에 올립니다.
     */
    @Override
    public void blacklistRefreshToken(String refreshToken, Duration remainingTime) {
        redisTemplate.opsForValue().set(keys.refreshTokenBlacklist(refreshToken), "used", remainingTime);
    }

    /**
     * @param accessToken 블랙리스트에 등록되었는지 확인할 액세스 토큰
     * @return 등록되었다면 true, 아니면 false
     * @responsibility 매 요청마다 불리는 경로이므로 <b>막지 않는 쪽</b>으로 실패합니다.
     * @implNote 감사 로그에 토큰을 그대로 적으면 로그 수집기가 유효한 자격 증명을 보관하게 됩니다.
     * 그래서 {@code hashCode()}가 아니라 SHA-256 단방향 해시로 남깁니다 — 사후에 "어느 토큰이
     * 검증을 우회했는가"를 대조할 수는 있으면서 로그만으로는 토큰을 복원할 수 없습니다.
     */
    @Override
    public boolean isAccessTokenBlacklisted(String accessToken) {
        if (!StringUtils.hasText(accessToken)) return false;

        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(keys.accessTokenBlacklist(accessToken)));

        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            log.warn("[SECURITY AUDIT] Redis 장애로 블랙리스트 검증 우회. Token Hash: {}, Reason: {}",
                    DigestUtils.sha256Hex(accessToken), e.getMessage());
            return false;

        } catch (DataAccessException e) {
            // 구체적인 예외 처리: Exception 대신 Spring Data(Redis) 관련 최상위 예외만 캐치
            log.error("Redis 데이터 접근 오류: {}", e.getMessage());
            return false;
        }
    }

    /**
     * @param refreshToken 블랙리스트에 등록되었는지 확인할 리프레시 토큰
     * @return 등록되었다면 true, 아니면 false
     * @responsibility 리프레시 토큰이 회전이나 로그아웃으로 죽었는지 확인합니다.
     * @implNote 액세스 토큰 경로와 달리 예외를 잡지 않습니다. 재발급은 매 요청마다 일어나는 경로가
     * 아니어서 장애가 그대로 드러나는 편이 낫다고 보았지만, 두 경로의 비대칭 자체는
     * {@code docs/refactor/09-fat-port-token-persistence.md}에 판단 대기로 적혀 있습니다.
     */
    @Override
    public boolean isRefreshTokenBlacklisted(String refreshToken) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(keys.refreshTokenBlacklist(refreshToken)));
    }
}
