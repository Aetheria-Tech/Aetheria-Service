package com.serverbe.application.service;

import com.serverbe.application.port.out.dto.oauth.AccessTokenResult;
import com.serverbe.application.port.out.dto.oauth.OAuthUserInfoResult;
import com.serverbe.application.port.out.dto.oauth.RefreshTokenResult;
import com.serverbe.application.port.out.dto.oauth.TokenResult;
import com.serverbe.application.port.out.oauth.OAuthClientPort;
import com.serverbe.application.port.out.security.TokenProvider;
import com.serverbe.application.service.helper.AuthSessionManager;
import com.serverbe.application.service.helper.UserDataSyncManager;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.domain.exception.external.ExternalApiErrorCode;
import com.serverbe.domain.exception.external.ExternalApiException;
import com.serverbe.domain.model.user.User;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import com.serverbe.domain.model.user.vo.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link LoginService}는 소셜 로그인 요청을 받는 {@link com.serverbe.application.port.in.oauth.LoginUseCase}의 구현체입니다.
 */
@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock
    private OAuthClientPort kakaoClient;
    @Mock
    private UserDataSyncManager userDataSyncManager;
    @Mock
    private AuthSessionManager authSessionManager;
    @Mock
    private TokenProvider tokenProvider;

    private LoginService loginService;

    private static final Long USER_ID = 1L;
    private static final String DEVICE_ID = "device-abc";
    private static final String AUTH_CODE = "auth-code";

    @BeforeEach
    void setUp() {
        loginService = new LoginService(
                Map.of(OAuthProvider.KAKAO, kakaoClient), userDataSyncManager, authSessionManager, tokenProvider);
    }

    @Test
    @DisplayName("성공: 지원하는 소셜 제공자로 로그인하면 유저를 동기화하고 토큰을 발급하며 세션을 저장한다")
    void login_Success() {
        // given
        OAuthUserInfoResult oauthInfo = new OAuthUserInfoResult("kakao-123", OAuthProvider.KAKAO, "test@kakao.com", "닉네임", "social-refresh-token");
        given(kakaoClient.getUserInfo(AUTH_CODE, OAuthProvider.KAKAO)).willReturn(Mono.just(oauthInfo));

        User syncedUser = new User(USER_ID, "kakao-123", OAuthProvider.KAKAO, "test@kakao.com", "닉네임", Role.USER, null, "social-refresh-token");
        given(userDataSyncManager.syncUserByOAuth(oauthInfo)).willReturn(syncedUser);

        AccessTokenResult accessTokenResult = AccessTokenResult.of("access-token", 3600L);
        RefreshTokenResult refreshTokenResult = RefreshTokenResult.of("opaque-refresh-token", "refresh-name", Instant.now());
        given(tokenProvider.generateAccessToken(USER_ID, Role.USER)).willReturn(accessTokenResult);
        given(tokenProvider.generateRefreshToken(USER_ID, Role.USER)).willReturn(refreshTokenResult);

        // when
        Mono<TokenResult> resultMono = loginService.login(AUTH_CODE, OAuthProvider.KAKAO, DEVICE_ID);

        // then
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertThat(result.accessTokenResult().accessToken()).isEqualTo("access-token");
                    assertThat(result.refreshTokenResult().opaqueToken()).isEqualTo("opaque-refresh-token");
                    assertThat(result.role()).isEqualTo(Role.USER);
                })
                .verifyComplete();

        verify(authSessionManager).saveSession(USER_ID, DEVICE_ID, "opaque-refresh-token");
    }

    @Test
    @DisplayName("실패: 지원하지 않는 소셜 제공자로 로그인을 시도하면 UNSUPPORTED_SOCIAL_LOGIN 예외가 발생한다")
    void login_Fail_UnsupportedProvider() {
        // given: 조회표에는 KAKAO 어댑터뿐인데 GOOGLE 요청이 들어온 상황

        // when & then
        assertThatThrownBy(() -> loginService.login(AUTH_CODE, OAuthProvider.GOOGLE, DEVICE_ID))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.UNSUPPORTED_SOCIAL_LOGIN);

        verify(kakaoClient, never()).getUserInfo(anyString(), any());
        verify(authSessionManager, never()).saveSession(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("실패: 외부 소셜 API 호출이 실패하면 해당 예외가 그대로 전파되고 세션은 생성되지 않는다")
    void login_Fail_ExternalApiError_Propagates() {
        // given
        given(kakaoClient.getUserInfo(AUTH_CODE, OAuthProvider.KAKAO))
                .willReturn(Mono.error(new ExternalApiException(ExternalApiErrorCode.FAILED_SOCIAL_API, "카카오 서버 응답 지연")));

        // when & then
        StepVerifier.create(loginService.login(AUTH_CODE, OAuthProvider.KAKAO, DEVICE_ID))
                .expectErrorMatches(e -> e instanceof ExternalApiException ex && ex.getErrorCode() == ExternalApiErrorCode.FAILED_SOCIAL_API)
                .verify();

        verify(userDataSyncManager, never()).syncUserByOAuth(any());
        verify(authSessionManager, never()).saveSession(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("성공: 지원하는 제공자의 로그인 URL을 정상적으로 반환한다")
    void getSocialLoginUrl_Success() {
        // given
        given(kakaoClient.getLoginUrl()).willReturn("https://kauth.kakao.com/oauth/authorize?...");

        // when
        String url = loginService.getSocialLoginUrl(OAuthProvider.KAKAO);

        // then
        assertThat(url).isEqualTo("https://kauth.kakao.com/oauth/authorize?...");
    }

    @Test
    @DisplayName("실패: 지원하지 않는 제공자의 로그인 URL을 요청하면 UNSUPPORTED_SOCIAL_LOGIN 예외가 발생한다")
    void getSocialLoginUrl_Fail_UnsupportedProvider() {
        // given: 조회표에는 KAKAO 어댑터뿐이다.

        // when & then
        assertThatThrownBy(() -> loginService.getSocialLoginUrl(OAuthProvider.GOOGLE))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.UNSUPPORTED_SOCIAL_LOGIN);
    }
}
