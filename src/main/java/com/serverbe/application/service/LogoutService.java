package com.serverbe.application.service;

import com.serverbe.application.port.in.oauth.LogoutUseCase;
import com.serverbe.application.port.out.security.TokenResolver;
import com.serverbe.application.service.helper.AuthSessionManager;
import com.serverbe.application.config.SessionPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * @author Duskafka
 * @responsibility 사용자의 로그아웃 요청에 따라 인증 세션을 무효화하고 사용된 토큰의 재사용을 방지합니다.
 */
@Slf4j
@Service
public class LogoutService implements LogoutUseCase {

    private final AuthSessionManager authSessionManager;
    private final TokenResolver tokenResolver;
    private final Duration defaultRefreshTokenValidityMs;

    public LogoutService(AuthSessionManager authSessionManager, TokenResolver tokenResolver, SessionPolicy sessionPolicy) {
        this.authSessionManager = authSessionManager;
        this.tokenResolver = tokenResolver;
        this.defaultRefreshTokenValidityMs = sessionPolicy.refreshTokenTtl();
    }

    /**
     * @param accessToken  로그아웃에 사용된 액세스 토큰
     * @param refreshToken 무효화할 특정 리프레시 토큰
     * @param deviceId     기기 식별자
     */
    @Override
    public void logout(String accessToken, String refreshToken, String deviceId) {
        Long userId = tokenResolver.getIdFromToken(accessToken);

        // [수정] 1. 현재 세션의 남은 수명(TTL) 조회
        long sessionTtl = authSessionManager.getSessionRemainingTime(userId, deviceId);

        // 2. 리프레시 토큰 블랙리스트 등록
        // 세션이 이미 만료되었거나 조회가 안되면 0이 반환될 수 있음 -> 이 경우 안전하게 기본값 사용 고려
        Duration blacklistDuration = (sessionTtl > 0)
                ? Duration.ofMillis(sessionTtl)
                : defaultRefreshTokenValidityMs;

        authSessionManager.blacklistRefreshToken(refreshToken, blacklistDuration);

        log.info("[LOGOUT] RT 블랙리스트 등록 완료. (TTL: {}ms)", blacklistDuration.toMillis());

        // 3. 특정 기기 세션 삭제 (Redis에서 키 제거)
        authSessionManager.terminateSession(userId, deviceId);

        // 4. 액세스 토큰 차단 (기존 로직 유지)
        authSessionManager.blacklistAccessToken(accessToken);
    }

    /**
     * @param accessToken 로그아웃에 사용된 액세스 토큰
     */
    @Override
    public void globalLogout(String accessToken) {
        Long userId = tokenResolver.getIdFromToken(accessToken);

        // 모든 리프레시 토큰 삭제 (전체 세션 종료)
        authSessionManager.terminateAllSessions(userId);

        // 현재 사용 중인 액세스 토큰 차단
        authSessionManager.blacklistAccessToken(accessToken);

        log.warn("[SECURITY ALERT] 사용자(ID: {})의 모든 세션이 일괄 종료되었습니다.", userId);
    }
}