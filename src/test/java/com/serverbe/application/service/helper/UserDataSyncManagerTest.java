package com.serverbe.application.service.helper;

import com.serverbe.application.port.out.dto.oauth.OAuthUserInfoResult;
import com.serverbe.application.port.out.jpa.UserRepositoryPort;
import com.serverbe.domain.exception.server.DataIntegrityViolationException;
import com.serverbe.domain.exception.server.ServerErrorCode;
import com.serverbe.domain.model.user.User;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import com.serverbe.domain.model.user.vo.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link UserDataSyncManager}는 OAuth 로그인마다 "조회 후 없으면 삽입"을 수행합니다.
 * 이 패턴은 두 요청이 동시에 조회 단계를 통과할 수 있어 그 자체로는 중복을 막지 못하며,
 * 실제 방어선은 users(oauth_id, oauth_provider) 유니크 인덱스입니다.
 * 따라서 경합에서 진 요청이 500으로 죽지 않고 복구되는지가 이 클래스의 핵심 계약입니다.
 * <p>
 * 여기서 stub이 던지는 것은 <b>도메인</b> {@code DataIntegrityViolationException}입니다.
 * DB가 제약 위반을 알려 주는 방식(스프링 예외)을 도메인 언어로 옮기는 일은 영속성 어댑터의 몫이고,
 * 그 번역이 실제로 일어나는지는 {@code UserPersistenceAdapterTest}가 검증합니다.
 * 이 테스트가 맡는 것은 <b>번역된 예외를 받았을 때 복구하는가</b>입니다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class UserDataSyncManagerTest {

    private static final String OAUTH_ID = "kakao-12345";
    private static final OAuthProvider PROVIDER = OAuthProvider.KAKAO;

    @Mock
    private UserRepositoryPort userRepositoryPort;
    @Mock
    private PlatformTransactionManager transactionManager;

    private UserDataSyncManager userDataSyncManager;

    @BeforeEach
    void setUp() {
        userDataSyncManager = new UserDataSyncManager(userRepositoryPort, transactionManager);
    }

    private OAuthUserInfoResult oauthInfo() {
        return new OAuthUserInfoResult(OAUTH_ID, PROVIDER, "user@example.com", "러너", "refresh-token");
    }

    private User existingUser() {
        return new User(1L, OAUTH_ID, PROVIDER, "old@example.com", "옛닉네임", Role.USER, "상태메시지", "old-token");
    }

    @Test
    @DisplayName("신규 가입: 기존 회원이 없으면 새 유저를 저장해 반환한다")
    void syncUserByOAuth_NewUser_Registers() {
        // given
        given(userRepositoryPort.findByOauthId(OAUTH_ID, PROVIDER)).willReturn(Optional.empty());
        given(userRepositoryPort.save(any(User.class))).willAnswer(inv -> {
            User newUser = inv.getArgument(0);
            return new User(99L, newUser.oauthId(), newUser.provider(), newUser.email(),
                    newUser.nickname(), newUser.role(), newUser.statusMessage(), newUser.oauthRefreshToken());
        });

        // when
        User result = userDataSyncManager.syncUserByOAuth(oauthInfo());

        // then
        assertThat(result.id()).isEqualTo(99L);
        assertThat(result.oauthId()).isEqualTo(OAUTH_ID);
        assertThat(result.role()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("기존 회원: 소셜에서 받은 최신 프로필과 리프레시 토큰으로 갱신해 저장한다")
    void syncUserByOAuth_ExistingUser_Refreshes() {
        // given
        given(userRepositoryPort.findByOauthId(OAUTH_ID, PROVIDER)).willReturn(Optional.of(existingUser()));
        given(userRepositoryPort.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        User result = userDataSyncManager.syncUserByOAuth(oauthInfo());

        // then
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.email()).isEqualTo("user@example.com");
        assertThat(result.nickname()).isEqualTo("러너");
        assertThat(result.oauthRefreshToken()).isEqualTo("refresh-token");
        // 프로필 수정으로만 바뀌는 값은 OAuth 동기화가 건드리지 않는다
        assertThat(result.statusMessage()).isEqualTo("상태메시지");
    }

    @Test
    @DisplayName("경합 복구: INSERT가 유니크 제약에 걸리면 재조회해 먼저 커밋된 회원으로 로그인시킨다")
    void syncUserByOAuth_ConcurrentFirstLogin_RecoversByRequery() {
        // given: 두 요청이 동시에 조회를 통과했고, 이 요청은 INSERT 경합에서 졌다.
        given(userRepositoryPort.findByOauthId(OAUTH_ID, PROVIDER))
                .willReturn(Optional.empty())          // 최초 조회: 아직 아무도 없다
                .willReturn(Optional.of(existingUser())); // 복구 조회: 이긴 쪽의 행이 커밋되어 있다

        given(userRepositoryPort.save(any(User.class)))
                .willThrow(new DataIntegrityViolationException(
                        ServerErrorCode.INTERNAL_SERVER_ERROR, "Duplicate entry for key 'uk_users_oauth'"))
                .willAnswer(inv -> inv.getArgument(0));

        // when
        User result = userDataSyncManager.syncUserByOAuth(oauthInfo());

        // then: 500이 아니라 정상 로그인으로 마무리된다
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.nickname()).isEqualTo("러너");
    }

    @Test
    @DisplayName("복구 불가: 제약 위반인데 재조회에도 없으면 원 예외를 그대로 전파한다")
    void syncUserByOAuth_ConstraintViolationWithoutRow_PropagatesException() {
        // given: 유니크 제약이 아닌 다른 무결성 위반일 수 있으므로 삼키지 않는다.
        given(userRepositoryPort.findByOauthId(OAUTH_ID, PROVIDER)).willReturn(Optional.empty());
        given(userRepositoryPort.save(any(User.class)))
                .willThrow(new DataIntegrityViolationException(
                        ServerErrorCode.INTERNAL_SERVER_ERROR, "email 컬럼 제약 위반"));

        // when & then
        assertThatThrownBy(() -> userDataSyncManager.syncUserByOAuth(oauthInfo()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("email 컬럼 제약 위반");
    }
}
