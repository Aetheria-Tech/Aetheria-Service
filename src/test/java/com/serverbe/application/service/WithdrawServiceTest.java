package com.serverbe.application.service;

import com.serverbe.application.port.out.jpa.UserRepositoryPort;
import com.serverbe.application.port.out.oauth.OAuthClientPort;
import com.serverbe.application.service.helper.UserDataCleanupManager;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.domain.exception.user.UserErrorCode;
import com.serverbe.domain.exception.user.UserException;
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

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link WithdrawService}는 {@link com.serverbe.application.port.in.oauth.WithdrawUseCase}의 구현체입니다.
 */
@ExtendWith(MockitoExtension.class)
class WithdrawServiceTest {

    @Mock
    private UserRepositoryPort userRepositoryPort;
    @Mock
    private OAuthClientPort kakaoClient;
    @Mock
    private UserDataCleanupManager userDataCleanupManager;

    private WithdrawService withdrawService;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        withdrawService = new WithdrawService(
                userRepositoryPort, Map.of(OAuthProvider.KAKAO, kakaoClient), userDataCleanupManager);
    }

    private User withdrawTargetUser() {
        return new User(USER_ID, "oauth-1", OAuthProvider.KAKAO, "test@test.com", "닉네임", Role.USER, null, "social-refresh-token");
    }

    @Test
    @DisplayName("성공: 소셜 연동 해제에 성공하면 내부 데이터를 파기하고 true를 반환한다")
    void withdraw_Success_UnlinkSucceeds() {
        // given
        given(userRepositoryPort.findById(USER_ID)).willReturn(Optional.of(withdrawTargetUser()));
        given(kakaoClient.unlink(OAuthProvider.KAKAO, "oauth-1", "social-refresh-token")).willReturn(Mono.just(true));

        // when & then
        StepVerifier.create(withdrawService.withdraw(USER_ID))
                .expectNext(true)
                .verifyComplete();

        verify(userDataCleanupManager).deleteAllUserData(USER_ID);
    }

    @Test
    @DisplayName("성공(거부): 소셜 연동 해제가 거부되면 내부 데이터를 파기하지 않고 false를 반환한다")
    void withdraw_Success_UnlinkRejected_DoesNotCleanUpData() {
        // given
        given(userRepositoryPort.findById(USER_ID)).willReturn(Optional.of(withdrawTargetUser()));
        given(kakaoClient.unlink(OAuthProvider.KAKAO, "oauth-1", "social-refresh-token")).willReturn(Mono.just(false));

        // when & then
        StepVerifier.create(withdrawService.withdraw(USER_ID))
                .expectNext(false)
                .verifyComplete();

        verify(userDataCleanupManager, never()).deleteAllUserData(anyLong());
    }

    @Test
    @DisplayName("실패: 존재하지 않는 사용자가 탈퇴를 요청하면 NOT_FOUND_USER 예외가 발생한다")
    void withdraw_Fail_UserNotFound() {
        // given
        given(userRepositoryPort.findById(USER_ID)).willReturn(Optional.empty());

        // when & then
        StepVerifier.create(withdrawService.withdraw(USER_ID))
                .expectErrorMatches(e -> e instanceof UserException ue && ue.getErrorCode() == UserErrorCode.NOT_FOUND_USER)
                .verify();

        verify(userDataCleanupManager, never()).deleteAllUserData(anyLong());
    }

    @Test
    @DisplayName("실패: 사용자의 소셜 제공자를 담당하는 클라이언트가 없으면 UNSUPPORTED_SOCIAL_LOGIN 예외가 발생한다")
    void withdraw_Fail_UnsupportedProvider() {
        // given: 구글로 가입한 사용자인데 조회표에는 카카오 어댑터뿐이다.
        User googleUser = new User(
                USER_ID, "oauth-2", OAuthProvider.GOOGLE, "test@test.com", "닉네임", Role.USER, null, "social-refresh-token");
        given(userRepositoryPort.findById(USER_ID)).willReturn(Optional.of(googleUser));

        // when & then
        StepVerifier.create(withdrawService.withdraw(USER_ID))
                .expectErrorMatches(e -> e instanceof AuthException ae && ae.getErrorCode() == AuthErrorCode.UNSUPPORTED_SOCIAL_LOGIN)
                .verify();

        verify(userDataCleanupManager, never()).deleteAllUserData(anyLong());
    }
}
