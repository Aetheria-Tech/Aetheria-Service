package com.serverbe.application.service;

import com.serverbe.application.port.out.dto.me.UserProfileResult;
import com.serverbe.application.port.out.dto.me.UserUpdateCommand;
import com.serverbe.application.port.out.jpa.UserRepositoryPort;
import com.serverbe.domain.exception.user.UserErrorCode;
import com.serverbe.domain.exception.user.UserException;
import com.serverbe.domain.model.user.User;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import com.serverbe.domain.model.user.vo.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link UserService}는 {@link com.serverbe.application.port.in.me.GetUserUseCase}와
 * {@link com.serverbe.application.port.in.me.UpdateUserUseCase}를 모두 구현하는 요청 진입점입니다.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepositoryPort userRepositoryPort;

    private UserService userService;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepositoryPort);
    }

    private User existingUser() {
        return new User(USER_ID, "oauth-1", OAuthProvider.KAKAO, "test@test.com", "기존닉네임", Role.USER, "기존상태메시지", "refresh-token");
    }

    @Test
    @DisplayName("성공: 존재하는 사용자의 프로필을 조회한다")
    void getMyProfile_Success() {
        // given
        given(userRepositoryPort.findById(USER_ID)).willReturn(Optional.of(existingUser()));

        // when
        UserProfileResult result = userService.getMyProfile(USER_ID);

        // then
        assertThat(result.email()).isEqualTo("test@test.com");
        assertThat(result.nickname()).isEqualTo("기존닉네임");
        assertThat(result.statusMessage()).isEqualTo("기존상태메시지");
    }

    @Test
    @DisplayName("실패: 존재하지 않는 사용자를 조회하면 NOT_FOUND_USER 예외가 발생한다")
    void getMyProfile_Fail_NotFound() {
        // given
        given(userRepositoryPort.findById(USER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.getMyProfile(USER_ID))
                .isInstanceOf(UserException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.NOT_FOUND_USER);
    }

    @Test
    @DisplayName("성공: 닉네임과 상태 메시지를 수정하면 변경된 프로필을 반환하고 영속화한다")
    void updateMyProfile_Success() {
        // given
        given(userRepositoryPort.findById(USER_ID)).willReturn(Optional.of(existingUser()));
        UserUpdateCommand command = new UserUpdateCommand("새닉네임", "새상태메시지");

        // when
        UserProfileResult result = userService.updateMyProfile(USER_ID, command);

        // then
        assertThat(result.nickname()).isEqualTo("새닉네임");
        assertThat(result.statusMessage()).isEqualTo("새상태메시지");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepositoryPort).save(captor.capture());
        assertThat(captor.getValue().nickname()).isEqualTo("새닉네임");
        assertThat(captor.getValue().statusMessage()).isEqualTo("새상태메시지");
        assertThat(captor.getValue().email()).isEqualTo("test@test.com"); // 이메일 등 다른 필드는 보존되어야 함
    }

    @Test
    @DisplayName("실패: 존재하지 않는 사용자를 수정하려고 하면 NOT_FOUND_USER 예외가 발생하고 저장은 시도되지 않는다")
    void updateMyProfile_Fail_NotFound() {
        // given
        given(userRepositoryPort.findById(USER_ID)).willReturn(Optional.empty());
        UserUpdateCommand command = new UserUpdateCommand("새닉네임", "새상태메시지");

        // when & then
        assertThatThrownBy(() -> userService.updateMyProfile(USER_ID, command))
                .isInstanceOf(UserException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.NOT_FOUND_USER);

        verify(userRepositoryPort, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
