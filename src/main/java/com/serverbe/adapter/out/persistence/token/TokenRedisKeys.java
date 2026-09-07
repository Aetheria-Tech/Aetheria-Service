package com.serverbe.adapter.out.persistence.token;

import com.serverbe.infrastructure.config.properties.RedisProperties;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

/**
 * @author Duskafka
 * @responsibility 토큰 관련 Redis 키를 조립하는 <b>유일한 자리</b>입니다.
 * @implSpec 키 모양은 배포 사이에 지켜져야 하는 계약입니다. 모양이 바뀌면 그 순간 살아 있던 세션이
 * 전부 무효가 되고, 블랙리스트에 올려 둔 토큰이 되살아납니다. 컴파일러가 잡아 주지 않으므로
 * {@code RefreshTokenSessionAdapterTest}와 {@code TokenBlacklistAdapterTest}가 다섯 가지 모양을
 * 실제 인스턴스로 고정합니다 — 이 클래스를 목으로 두면 고정하려던 것이 사라집니다.
 * @implNote <b>이 클래스가 존재하는 이유는 두 어댑터가 키 하나를 공유하기 때문입니다.</b>
 * 세션 어댑터와 블랙리스트 어댑터는 서로 다른 포트를 구현하지만,
 * {@code rotateRefreshToken}이 구 토큰의 <b>RT 블랙리스트 키</b>를 만들어 Lua 스크립트에 넘깁니다.
 * 양쪽이 각자 조립하면 한쪽 모양이 바뀌는 날 다른 쪽이 조용히 어긋나고, 그 증상은
 * "회전한 구 토큰이 계속 유효하다"로 나타납니다.
 */
@Component
public class TokenRedisKeys {

    private final String authPrefix;
    private final String authSuffix;
    private final String sessionSuffix;
    private final String atBlacklistPrefix;
    private final String rtBlacklistPrefix;

    public TokenRedisKeys(RedisProperties redisProperties) {
        this.authPrefix = redisProperties.auth().prefix();
        this.authSuffix = redisProperties.auth().suffix();
        this.sessionSuffix = redisProperties.session().suffix();
        this.atBlacklistPrefix = redisProperties.blacklist().accessTokenPrefix();
        this.rtBlacklistPrefix = redisProperties.blacklist().refreshTokenPrefix();
    }

    /**
     * @responsibility 기기별 리프레시 토큰이 담기는 String 키를 만듭니다.
     * @implNote 기기마다 키를 나눈 이유는 <b>TTL이 기기마다 달라야 하기 때문</b>입니다.
     * 결과 예시: {@code user:100:rt:device-A}
     */
    public String token(Long userId, String deviceId) {
        return String.format("%s:%d:%s:%s", authPrefix, userId, authSuffix, deviceId);
    }

    /**
     * @responsibility 로그인 시각순으로 정렬된 기기 인덱스(ZSet)의 키를 만듭니다.
     * @implNote 결과 예시: {@code user:session:100}
     */
    public String sessionIndex(Long userId) {
        return String.format("%s:%s:%s", authPrefix, sessionSuffix, userId);
    }

    /**
     * @responsibility Lua 스크립트가 deviceId와 결합해 토큰 키를 재조립할 접두사를 만듭니다.
     * @implNote {@link #token}에서 deviceId만 뺀 부분입니다(끝의 콜론 포함). 스크립트가 인덱스에서
     * 읽은 deviceId로 키를 직접 만들어야 하므로, 조립 규칙의 절반을 스크립트에 넘겨 주는 셈입니다.
     * 결과 예시: {@code user:100:rt:}
     */
    public String tokenPrefix(Long userId) {
        return String.format("%s:%d:%s:", authPrefix, userId, authSuffix);
    }

    /**
     * @responsibility 액세스 토큰 블랙리스트 키를 만듭니다.
     * @implNote Redis 내에 토큰 원문이 남지 않도록 SHA-256 해싱을 적용합니다. ({@code BL:AT:{hash}})
     */
    public String accessTokenBlacklist(String accessToken) {
        return String.format("%s:%s", atBlacklistPrefix, DigestUtils.sha256Hex(accessToken));
    }

    /**
     * @responsibility 리프레시 토큰 블랙리스트 키를 만듭니다.
     * @implNote Redis 내에 토큰 원문이 남지 않도록 SHA-256 해싱을 적용합니다. ({@code BL:RT:{hash}})
     */
    public String refreshTokenBlacklist(String refreshToken) {
        return String.format("%s:%s", rtBlacklistPrefix, DigestUtils.sha256Hex(refreshToken));
    }
}
