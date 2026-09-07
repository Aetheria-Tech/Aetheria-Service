package com.serverbe.adapter.out.persistence.token;

import com.serverbe.application.port.out.token.RefreshTokenSessionPort;
import com.serverbe.infrastructure.config.properties.JwtProperties;
import com.serverbe.infrastructure.config.properties.RedisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * @author Duskafka
 * @responsibility Redis를 활용하여 멀티 디바이스 환경의 <b>리프레시 토큰 세션</b>을 관리합니다.
 * @implSpec 1. <b>Token Data</b>: String 구조로 기기별 독립 TTL 관리. (Key: {@code auth:{userId}:rt:{deviceId}})<br>
 * 2. <b>Session Index</b>: ZSet을 사용하여 로그인 시간순 정렬 및 기기 수 제한 관리. (Key: {@code auth:sessions:{userId}})
 * @implNote <b>Lua 스크립트 넷이 전부 이 어댑터에 있습니다.</b> 원자성이 필요한 복합 연산이 세션
 * 쪽에만 몰려 있다는 뜻이고, 근거는 {@code docs/troubleshooting/06-refresh-token-rotation.md}입니다.
 * 키 조립은 {@link TokenRedisKeys}가 전담합니다 — 회전이 만드는 블랙리스트 키를
 * {@code TokenBlacklistAdapter}와 공유해야 하기 때문입니다.
 */
@Slf4j
@Component
public class RefreshTokenSessionAdapter implements RefreshTokenSessionPort {

    /**
     * 구 토큰의 PTTL 조회가 실패했을 때 쓰는 블랙리스트 TTL 대체값입니다.
     */
    private static final Duration FALLBACK_BLACKLIST_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final TokenRedisKeys keys;
    private final int maxToken;
    private final Duration refreshTokenExpireDays;

    // Lua Scripts 주입
    private final RedisScript<Boolean> saveTokenScript;
    private final RedisScript<Boolean> rotateTokenScript;
    private final RedisScript<Boolean> globalLogoutScript;
    private final RedisScript<Boolean> deleteTokenScript;

    public RefreshTokenSessionAdapter(
            StringRedisTemplate redisTemplate,
            TokenRedisKeys keys,
            RedisProperties redisProperties,
            JwtProperties jwtProperties,

            @Qualifier("saveTokenScript") RedisScript<Boolean> saveTokenScript,
            @Qualifier("rotateTokenScript") RedisScript<Boolean> rotateTokenScript,
            @Qualifier("globalLogoutScript") RedisScript<Boolean> globalLogoutScript,
            @Qualifier("deleteTokenScript") RedisScript<Boolean> deleteTokenScript
    ) {
        this.redisTemplate = redisTemplate;
        this.keys = keys;
        this.saveTokenScript = saveTokenScript;
        this.rotateTokenScript = rotateTokenScript;
        this.globalLogoutScript = globalLogoutScript;
        this.deleteTokenScript = deleteTokenScript;

        this.maxToken = redisProperties.auth().maxToken();
        this.refreshTokenExpireDays = jwtProperties.refreshToken().expirationDays();
    }

    /**
     * @responsibility 토큰 저장 및 세션 인덱스 업데이트
     * @implSpec Lua Script를 통해 세션 개수 제한(Max Token) 확인과 정리를 원자적으로 수행합니다.
     */
    @Override
    public void saveRefreshToken(Long userId, String deviceId, String refreshToken, Duration expiry) {
        redisTemplate.execute(saveTokenScript,
                List.of(keys.sessionIndex(userId), keys.token(userId, deviceId)), // KEYS
                deviceId,                                          // ARGV[1]
                refreshToken,                                      // ARGV[2]
                String.valueOf(expiry.toMillis()),                 // ARGV[3]
                String.valueOf(System.currentTimeMillis()),        // ARGV[4]
                String.valueOf(refreshTokenExpireDays.toMillis()), // ARGV[5]
                String.valueOf(maxToken),                          // ARGV[6]
                keys.tokenPrefix(userId)                           // ARGV[7]
        );
    }

    /**
     * @responsibility 특정 기기의 토큰 값 조회
     */
    @Override
    public String getRefreshToken(Long userId, String deviceId) {
        Object token = redisTemplate.opsForValue().get(keys.token(userId, deviceId));
        return token != null ? token.toString() : null;
    }

    /**
     * @responsibility 특정 기기의 세션 로그아웃
     */
    @Override
    public void deleteRefreshToken(Long userId, String deviceId) {
        redisTemplate.execute(deleteTokenScript,
                List.of(keys.sessionIndex(userId), keys.token(userId, deviceId)), // KEYS
                deviceId                                                          // ARGV[1]
        );

        log.debug("[SESSION] 개별 로그아웃 완료. userId={}, deviceId={}", userId, deviceId);
    }

    /**
     * @param userId 세션을 모두 종료할 유저 고유 식별자
     * @responsibility 해당 사용자의 모든 인증 세션 데이터(개별 토큰 및 세션 인덱스)를 완전히 제거하여 전역 로그아웃(Global Logout)을 수행합니다.
     * @implSpec <b>Atomicity (원자성)</b>: Lua Script를 사용하여 다음을 보장합니다.<br>
     * 1. <b>Isolation</b>: 스크립트 실행 중에는 다른 커맨드가 끼어들 수 없어 데이터 정합성이 보장됩니다.<br>
     * 2. <b>Cleanup</b>: 세션 인덱스(ZSet)에 등록된 모든 기기별 토큰 키를 순회 삭제하고 인덱스 자체도 제거합니다.
     */
    @Override
    public void deleteAllRefreshTokens(Long userId) {
        redisTemplate.execute(globalLogoutScript,
                List.of(keys.sessionIndex(userId)), // KEYS
                keys.tokenPrefix(userId)            // ARGV
        );

        log.info("[SESSION] 전역 로그아웃 완료. User={}", userId);
    }

    /**
     * @param userId 사용자 식별자
     * @responsibility 현재 접속 중인 기기 목록(Device ID)을 조회합니다.
     * @implNote StringRedisTemplate을 사용하여 별도의 형변환 없이 문자열 데이터를 반환합니다.
     */
    @Override
    public Set<String> getAllDeviceIds(Long userId) {
        Set<String> deviceIds = redisTemplate.opsForZSet().range(keys.sessionIndex(userId), 0, -1);

        return deviceIds == null ? Collections.emptySet() : deviceIds;
    }

    /**
     * @param userId 사용자 식별자
     * @responsibility 가장 오래된 세션 1개 강제 삭제
     */
    @Override
    public void removeOldestSession(Long userId) {
        Set<String> oldestDeviceSet = redisTemplate.opsForZSet().range(keys.sessionIndex(userId), 0, 0);

        if (oldestDeviceSet != null && !oldestDeviceSet.isEmpty()) {
            String oldestDeviceId = oldestDeviceSet.iterator().next();
            // 해당 기기 삭제 위임
            deleteRefreshToken(userId, oldestDeviceId);
            log.info("[SESSION] 세션이 한도를 초과하였습니다. 가장 오래된 세션을 삭제합니다.: userId={}, deviceId={}", userId, oldestDeviceId);
        }
    }

    /**
     * @param userId 사용자 식별자
     * @responsibility 현재 활성 세션 개수 조회
     */
    @Override
    public long getSessionCount(Long userId) {
        Long count = redisTemplate.opsForZSet().zCard(keys.sessionIndex(userId));
        return count != null ? count : 0;
    }

    /**
     * @param userId       사용자 식별자
     * @param deviceId     기기 식별자
     * @param refreshToken 존재하는지 확인할 리프레시 토큰
     * @responsibility 토큰 검증
     */
    @Override
    public boolean existsRefreshToken(Long userId, String deviceId, String refreshToken) {
        String storedToken = getRefreshToken(userId, deviceId);
        return storedToken != null && storedToken.equals(refreshToken);
    }

    /**
     * 리프레시 토큰 순환 메소드
     *
     * @param userId          사용자 식별자
     * @param deviceId        기기 식별자
     * @param oldRefreshToken 오래된(이전의) 리프레시 토큰
     * @param newRefreshToken 새로 발급한 리프레시 토큰
     * @param newTokensExpiry 새로 발급될 토큰의 유효 기간
     * @responsibility 리프레시 토큰 순환(RTR) 및 보안 처리
     * @implSpec 기존 토큰 무효화(Blacklist)와 신규 토큰 발급을 단일 트랜잭션(Lua)으로 처리합니다.
     * @implNote <b>이 어댑터가 블랙리스트 키를 만드는 유일한 지점입니다.</b> 무효화만 떼어
     * {@code TokenBlacklistPort}에 맡기면 두 호출 사이에서 프로세스가 죽었을 때 구 토큰이 살아 있는
     * 상태가 남습니다. 그래서 키를 {@link TokenRedisKeys}에서 받아 스크립트에 함께 넘깁니다.
     */
    @Override
    public void rotateRefreshToken(Long userId, String deviceId, String oldRefreshToken, String newRefreshToken, Duration newTokensExpiry) {
        redisTemplate.execute(rotateTokenScript,
                List.of(keys.sessionIndex(userId),
                        keys.token(userId, deviceId),
                        keys.refreshTokenBlacklist(oldRefreshToken)),  // KEYS
                deviceId,                                              // ARGV[1]
                newRefreshToken,                                       // ARGV[2]
                String.valueOf(newTokensExpiry.toMillis()),            // ARGV[3]
                String.valueOf(System.currentTimeMillis()),            // ARGV[4]
                String.valueOf(refreshTokenExpireDays.toMillis()),     // ARGV[5]
                String.valueOf(maxToken),                              // ARGV[6]
                keys.tokenPrefix(userId),                              // ARGV[7]
                String.valueOf(FALLBACK_BLACKLIST_TTL.toMillis())      // ARGV[8]
        );

        log.info("[RTR] 토큰 교체 완료 (Lua Script). userId={}, deviceId={}", userId, deviceId);
    }

    /**
     * @responsibility 특정 기기 세션의 남은 수명을 밀리초로 반환합니다.
     * @implNote 만료되었거나 키가 없으면 0을 돌려줍니다. Redis는 이 둘을 각각 -2, -1로 구분하지만
     * 호출자에게는 "얼마 남았는가"만 의미가 있습니다.
     */
    @Override
    public long getSessionTtl(Long userId, String deviceId) {
        Long expire = redisTemplate.getExpire(keys.token(userId, deviceId), TimeUnit.MILLISECONDS);

        return (expire != null && expire > 0) ? expire : 0;
    }
}
