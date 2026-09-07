package com.serverbe.application.service;

import com.serverbe.application.port.out.dto.oauth.AccessTokenResult;
import com.serverbe.application.port.out.dto.oauth.RefreshTokenResult;
import com.serverbe.application.port.out.dto.oauth.TokenResult;
import com.serverbe.application.port.out.security.TokenProvider;
import com.serverbe.application.port.out.security.TokenResolver;
import com.serverbe.application.service.helper.AuthSessionManager;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.domain.model.user.vo.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReissueService}는 {@link com.serverbe.application.port.in.token.ReissueUseCase}의 구현체이며,
 * RTR(Refresh Token Rotation) 및 재사용(Replay) 공격 탐지를 담당합니다.
 */
@ExtendWith(MockitoExtension.class)
class ReissueServiceTest {

    @Mock
    private AuthSessionManager authSessionManager;
    @Mock
    private TokenProvider tokenProvider;
    @Mock
    private TokenResolver tokenResolver;

    private ReissueService reissueService;

    private static final Long USER_ID = 1L;
    private static final String DEVICE_ID = "device-abc";
    private static final String OLD_ACCESS_TOKEN = "old-access-token";
    private static final String OLD_REFRESH_TOKEN = "old-refresh-token";

    @BeforeEach
    void setUp() {
        reissueService = new ReissueService(authSessionManager, tokenProvider, tokenResolver);
    }

    @Test
    @DisplayName("성공: 유효한 리프레시 토큰으로 재발급 요청 시 신규 토큰이 발급되고 세션이 로테이션된다")
    void reissue_Success() {
        // given
        given(tokenResolver.getIdFromToken(OLD_ACCESS_TOKEN)).willReturn(USER_ID);
        given(tokenResolver.getRoleFromToken(OLD_ACCESS_TOKEN)).willReturn(Role.USER);
        given(tokenResolver.validateRefreshToken(OLD_REFRESH_TOKEN)).willReturn(true);
        given(authSessionManager.isRefreshTokenBlacklisted(OLD_REFRESH_TOKEN)).willReturn(false);
        given(authSessionManager.isSessionValid(USER_ID, DEVICE_ID, OLD_REFRESH_TOKEN)).willReturn(true);

        AccessTokenResult newAccessToken = AccessTokenResult.of("new-access-token", 3600L);
        RefreshTokenResult newRefreshToken = RefreshTokenResult.of("new-refresh-token", "name", Instant.now());
        given(tokenProvider.generateAccessToken(USER_ID, Role.USER)).willReturn(newAccessToken);
        given(tokenProvider.generateRefreshToken(USER_ID, Role.USER)).willReturn(newRefreshToken);

        // when
        TokenResult result = reissueService.reissue(OLD_ACCESS_TOKEN, OLD_REFRESH_TOKEN, DEVICE_ID);

        // then
        assertThat(result.accessTokenResult().accessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshTokenResult().opaqueToken()).isEqualTo("new-refresh-token");

        verify(authSessionManager).blacklistAccessToken(OLD_ACCESS_TOKEN);
        verify(authSessionManager).rotateSession(USER_ID, DEVICE_ID, OLD_REFRESH_TOKEN, "new-refresh-token");
        verify(authSessionManager, never()).terminateAllSessions(USER_ID);
    }

    @Test
    @DisplayName("실패: 리프레시 토큰 형식이 유효하지 않으면 REFRESH_TOKEN_NOT_EXIST 예외가 발생하고 세션 파괴는 일어나지 않는다")
    void reissue_Fail_InvalidTokenFormat() {
        // given
        given(tokenResolver.getIdFromToken(OLD_ACCESS_TOKEN)).willReturn(USER_ID);
        given(tokenResolver.getRoleFromToken(OLD_ACCESS_TOKEN)).willReturn(Role.USER);
        given(tokenResolver.validateRefreshToken(OLD_REFRESH_TOKEN)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> reissueService.reissue(OLD_ACCESS_TOKEN, OLD_REFRESH_TOKEN, DEVICE_ID))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.REFRESH_TOKEN_NOT_EXIST);

        // 형식 자체가 잘못된 것은 보안 침해로 간주하지 않으므로 전역 로그아웃까지는 트리거하지 않는다
        verify(authSessionManager, never()).terminateAllSessions(USER_ID);
    }

    @Test
    @DisplayName("실패(보안): 이미 블랙리스트에 등록된(재사용된) 리프레시 토큰으로 접근하면 모든 세션이 파괴된다")
    void reissue_Fail_BlacklistedToken_TriggersGlobalLogout() {
        // given
        given(tokenResolver.getIdFromToken(OLD_ACCESS_TOKEN)).willReturn(USER_ID);
        given(tokenResolver.getRoleFromToken(OLD_ACCESS_TOKEN)).willReturn(Role.USER);
        given(tokenResolver.validateRefreshToken(OLD_REFRESH_TOKEN)).willReturn(true);
        given(authSessionManager.isRefreshTokenBlacklisted(OLD_REFRESH_TOKEN)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> reissueService.reissue(OLD_ACCESS_TOKEN, OLD_REFRESH_TOKEN, DEVICE_ID))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.REISSUE_FAILED);

        verify(authSessionManager).terminateAllSessions(USER_ID);
        verify(tokenProvider, never()).generateAccessToken(USER_ID, Role.USER);
        verify(authSessionManager, never()).rotateSession(org.mockito.ArgumentMatchers.eq(USER_ID), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("실패(보안): 저장된 세션과 일치하지 않는 리프레시 토큰으로 접근하면 모든 세션이 파괴된다")
    void reissue_Fail_SessionMismatch_TriggersGlobalLogout() {
        // given
        given(tokenResolver.getIdFromToken(OLD_ACCESS_TOKEN)).willReturn(USER_ID);
        given(tokenResolver.getRoleFromToken(OLD_ACCESS_TOKEN)).willReturn(Role.USER);
        given(tokenResolver.validateRefreshToken(OLD_REFRESH_TOKEN)).willReturn(true);
        given(authSessionManager.isRefreshTokenBlacklisted(OLD_REFRESH_TOKEN)).willReturn(false);
        given(authSessionManager.isSessionValid(USER_ID, DEVICE_ID, OLD_REFRESH_TOKEN)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> reissueService.reissue(OLD_ACCESS_TOKEN, OLD_REFRESH_TOKEN, DEVICE_ID))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.REISSUE_FAILED);

        verify(authSessionManager).terminateAllSessions(USER_ID);
    }
}
