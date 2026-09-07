package com.serverbe.application.service.helper;

import com.serverbe.application.port.out.security.TokenResolver;
import com.serverbe.application.port.out.token.RefreshTokenSessionPort;
import com.serverbe.application.port.out.token.TokenBlacklistPort;
import com.serverbe.application.config.SessionPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * @author Duskafka
 * @responsibility 멀티 디바이스 환경에서 인증 세션의 생명주기(생성, 검증, 파기, 교체)를 관리합니다.
 * @implSpec 1. <b>Device Isolation</b>: `UserId`와 `DeviceId`를 조합하여 기기별로 독립적인 세션을 관리합니다.<br>
 * 2. <b>Security Policy</b>: 액세스 토큰 블랙리스트 처리 및 RTR(Refresh Token Rotation) 시 토큰 갱신을 담당합니다.
 * @implNote <b>포트를 둘 주입받는 것이 이 클래스의 사실입니다.</b> 로그아웃은 세션을 파기하면서
 * 액세스 토큰을 블랙리스트에 올리는데, 그 둘은 키의 주인이 다릅니다 — 세션은 사용자가, 블랙리스트는
 * 토큰 문자열이 주인입니다. 하나의 넓은 포트로 받으면 이 지점이 감춰집니다.
 */
@Slf4j
@Component
public class AuthSessionManager {

    private final RefreshTokenSessionPort refreshTokenSessionPort;
    private final TokenBlacklistPort tokenBlacklistPort;
    private final Duration refreshTokenExpirationDays;
    private final TokenResolver tokenResolver;

    public AuthSessionManager(
            RefreshTokenSessionPort refreshTokenSessionPort,
            TokenBlacklistPort tokenBlacklistPort,
            SessionPolicy sessionPolicy,
            TokenResolver tokenResolver
    ) {
        this.refreshTokenSessionPort = refreshTokenSessionPort;
        this.tokenBlacklistPort = tokenBlacklistPort;
        this.refreshTokenExpirationDays = sessionPolicy.refreshTokenTtl();
        this.tokenResolver = tokenResolver;
    }

    /**
     * @param userId       유저 고유 식별자
     * @param deviceId     접속한 기기 식별자 (User-Agent 해시 또는 UUID)
     * @param refreshToken 저장할 리프레시 토큰
     * @responsibility 특정 기기에 대한 새로운 보안 세션을 생성하고 저장합니다.
     */
    public void saveSession(Long userId, String deviceId, String refreshToken) {
        refreshTokenSessionPort.saveRefreshToken(userId, deviceId, refreshToken, refreshTokenExpirationDays);
        log.info("[SESSION] 세션이 생성되었습니다. UserID: {}, DeviceID: {}, TTL: {} days", userId, deviceId, refreshTokenExpirationDays.toDays());
    }

    /**
     * @param userId   유저 고유 식별자
     * @param deviceId 로그아웃할 기기 식별자
     * @responsibility 특정 기기의 세션(토큰 및 인덱스)을 즉시 파기합니다. (로그아웃)
     */
    public void terminateSession(Long userId, String deviceId) {
        refreshTokenSessionPort.deleteRefreshToken(userId, deviceId);
        log.info("[SESSION] 기기 로그아웃 처리가 완료되었습니다. UserID: {}, DeviceID: {}", userId, deviceId);
    }

    /**
     * @param userId 세션을 모두 종료할 유저 고유 식별자
     * @responsibility 해당 사용자의 모든 기기 세션을 강제 종료합니다. (비밀번호 변경, 회원 탈퇴 등)
     */
    public void terminateAllSessions(Long userId) {
        refreshTokenSessionPort.deleteAllRefreshTokens(userId);
        log.warn("[SESSION] 사용자의 모든 기기 세션을 강제 종료했습니다. (Global Logout) UserID: {}", userId);
    }

    /**
     * @param accessToken    블랙리스트에 등록할 액세스 토큰
     * @responsibility 로그아웃된 액세스 토큰을 블랙리스트에 등록하여 재사용을 차단합니다.
     */
    public void blacklistAccessToken(String accessToken) {
        long remainingMillis = tokenResolver.getRemainingTimeFromAccessToken(accessToken);

        if (remainingMillis > 0) {
            Duration remainingTime = Duration.ofMillis(remainingMillis);
            tokenBlacklistPort.blacklistAccessToken(accessToken, remainingTime);
            log.info("[BLACKLIST] 액세스 토큰 차단 완료. 유지 시간: {} seconds", remainingTime.getSeconds());
        }
    }


    /**
     * @param userId       유저 고유 식별자
     * @param deviceId     요청한 기기 식별자
     * @param refreshToken 검증할 리프레시 토큰
     * @return 세션 유효 여부
     * @responsibility 해당 기기에 저장된 토큰과 요청된 토큰이 일치하는지 확인합니다.
     */
    public boolean isSessionValid(Long userId, String deviceId, String refreshToken) {
        boolean isValid = refreshTokenSessionPort.existsRefreshToken(userId, deviceId, refreshToken);
        if (!isValid) {
            log.warn("[SESSION] 세션 검증 실패. UserID: {}, DeviceID: {}", userId, deviceId);
        }
        return isValid;
    }

    public boolean isRefreshTokenBlacklisted(String token) {
        return tokenBlacklistPort.isRefreshTokenBlacklisted(token);
    }

    /**
     * @param userId          유저 고유 식별자
     * @param deviceId        요청한 기기 식별자
     * @param newRefreshToken 교체할 신규 토큰
     * @responsibility <b>RTR(Refresh Token Rotation)</b> 정책에 따라 해당 기기의 토큰을 갱신합니다.
     * @implNote 기존에는 `삭제 -> 저장` 방식이었으나, Key-Value 구조에서는 `덮어쓰기(Overwrite)`만으로 교체가 완료됩니다.
     */
    public void rotateSession(Long userId, String deviceId, String oldRefreshToken, String newRefreshToken) {
        // 리프레시 토큰의 최대 수명을 그대로 전달
        refreshTokenSessionPort.rotateRefreshToken(
                userId,
                deviceId,
                oldRefreshToken,
                newRefreshToken,
                refreshTokenExpirationDays
        );

        log.info("[SESSION] 리프레시 토큰 원자적 교체 완료. UserID: {}, DeviceID: {}", userId, deviceId);
    }

    public long getSessionRemainingTime(Long userId, String deviceId) {
        return refreshTokenSessionPort.getSessionTtl(userId, deviceId);
    }

    public void blacklistRefreshToken(String refreshToken, Duration duration) {
        tokenBlacklistPort.blacklistRefreshToken(refreshToken, duration);
    }
}