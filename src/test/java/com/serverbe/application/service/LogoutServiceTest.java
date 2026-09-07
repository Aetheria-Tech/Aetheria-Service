package com.serverbe.application.service;

import com.serverbe.application.port.out.security.TokenResolver;
import com.serverbe.application.service.helper.AuthSessionManager;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.application.config.SessionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link LogoutService}는 {@link com.serverbe.application.port.in.oauth.LogoutUseCase}의 구현체입니다.
 */
@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {

    @Mock
    private AuthSessionManager authSessionManager;
    @Mock
    private TokenResolver tokenResolver;

    private LogoutService logoutService;

    private static final Long USER_ID = 1L;
    private static final String DEVICE_ID = "device-abc";
    private static final String ACCESS_TOKEN = "access-token";
    private static final String REFRESH_TOKEN = "refresh-token";
    private static final Duration DEFAULT_RT_EXPIRATION = Duration.ofDays(14);

    @BeforeEach
    void setUp() {
        logoutService = new LogoutService(
                authSessionManager, tokenResolver, new SessionPolicy(DEFAULT_RT_EXPIRATION));
    }

    @Test
    @DisplayName("성공: 세션이 유효하면 남은 세션 TTL만큼 리프레시 토큰을 블랙리스트 등록하고 세션/액세스토큰을 정리한다")
    void logout_Success_UsesRemainingSessionTtl() {
        // given
        given(tokenResolver.getIdFromToken(ACCESS_TOKEN)).willReturn(USER_ID);
        given(authSessionManager.getSessionRemainingTime(USER_ID, DEVICE_ID)).willReturn(60_000L); // 60초 남음

        // when
        logoutService.logout(ACCESS_TOKEN, REFRESH_TOKEN, DEVICE_ID);

        // then
        verify(authSessionManager).blacklistRefreshToken(REFRESH_TOKEN, Duration.ofMillis(60_000L));
        verify(authSessionManager).terminateSession(USER_ID, DEVICE_ID);
        verify(authSessionManager).blacklistAccessToken(ACCESS_TOKEN);
    }

    @Test
    @DisplayName("성공(엣지): 세션 TTL 조회가 0 이하로 나오면(이미 만료) 기본 리프레시 토큰 만료 기간을 사용한다")
    void logout_Success_FallsBackToDefaultExpirationWhenSessionAlreadyExpired() {
        // given
        given(tokenResolver.getIdFromToken(ACCESS_TOKEN)).willReturn(USER_ID);
        given(authSessionManager.getSessionRemainingTime(USER_ID, DEVICE_ID)).willReturn(0L);

        // when
        logoutService.logout(ACCESS_TOKEN, REFRESH_TOKEN, DEVICE_ID);

        // then
        verify(authSessionManager).blacklistRefreshToken(REFRESH_TOKEN, DEFAULT_RT_EXPIRATION);
    }

    @Test
    @DisplayName("실패: 액세스 토큰이 유효하지 않아 유저 식별에 실패하면 세션 정리 로직은 전혀 수행되지 않는다")
    void logout_Fail_InvalidAccessToken_PropagatesAndSkipsCleanup() {
        // given
        given(tokenResolver.getIdFromToken(ACCESS_TOKEN))
                .willThrow(new AuthException(AuthErrorCode.JWT_TOKEN_IS_INVALID));

        // when & then
        assertThatThrownBy(() -> logoutService.logout(ACCESS_TOKEN, REFRESH_TOKEN, DEVICE_ID))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.JWT_TOKEN_IS_INVALID);

        verify(authSessionManager, never()).blacklistRefreshToken(anyString(), org.mockito.ArgumentMatchers.any());
        verify(authSessionManager, never()).terminateSession(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    @DisplayName("성공: 전역 로그아웃 요청 시 모든 세션을 종료하고 현재 액세스 토큰을 차단한다")
    void globalLogout_Success() {
        // given
        given(tokenResolver.getIdFromToken(ACCESS_TOKEN)).willReturn(USER_ID);

        // when
        logoutService.globalLogout(ACCESS_TOKEN);

        // then
        verify(authSessionManager).terminateAllSessions(USER_ID);
        verify(authSessionManager).blacklistAccessToken(ACCESS_TOKEN);
    }

    @Test
    @DisplayName("실패: 전역 로그아웃 시 토큰에서 유저 식별에 실패하면 세션 종료 로직이 수행되지 않는다")
    void globalLogout_Fail_InvalidAccessToken() {
        // given
        given(tokenResolver.getIdFromToken(ACCESS_TOKEN))
                .willThrow(new AuthException(AuthErrorCode.JWT_TOKEN_IS_INVALID));

        // when & then
        assertThatThrownBy(() -> logoutService.globalLogout(ACCESS_TOKEN))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.JWT_TOKEN_IS_INVALID);

        verify(authSessionManager, never()).terminateAllSessions(org.mockito.ArgumentMatchers.anyLong());
    }
}
