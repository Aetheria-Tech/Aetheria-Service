package com.serverbe.adapter.out.persistence.user;

import com.serverbe.adapter.out.persistence.mapper.UserMapper;
import com.serverbe.domain.exception.server.DataIntegrityViolationException;
import com.serverbe.domain.model.user.User;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import com.serverbe.domain.model.user.vo.Role;
import com.serverbe.infrastructure.crypto.EncryptionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPersistenceAdapterTest {

    @Mock
    private JpaUserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserPersistenceAdapter userPersistenceAdapter;

    @Test
    @DisplayName("구버전 암호화 데이터를 조회하면, 어댑터가 내부적으로 save를 호출하여 마이그레이션해야 한다")
    void shouldAutoUpdateWhenLegacyDataLoaded() {
        // given
        Long userId = 1L;
        UserEntity legacyEntity = UserEntity.builder()
                .id(userId)
                .email("v1:encrypted_email")
                .build();

        // record 생성자 호출
        User mockUser = new User(
                userId,
                "123",
                OAuthProvider.KAKAO,
                "test@test.com",
                "닉네임",
                Role.USER,
                null,
                null
        );

        // Stubbing
        when(userRepository.findById(userId)).thenReturn(Optional.of(legacyEntity));
        when(userMapper.toDomain(any(UserEntity.class))).thenReturn(mockUser);

        // saveAndFlush는 보통 void를 반환하거나, self를 반환합니다. JpaRepository<T, ID>를 따르므로 self를 반환합니다.
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenReturn(legacyEntity);

        // [중요] 마이그레이션이 필요하다는 플래그를 수동으로 세팅
        EncryptionContext.setMigrationRequired(true);

        // when
        userPersistenceAdapter.findById(userId);

        // then
        // 이제 어댑터 내부에서 saveAndFlush()가 호출될 것입니다.
        verify(userRepository, times(1)).saveAndFlush(any(UserEntity.class));
    }

    @Test
    @DisplayName("DB 제약 위반은 어댑터 경계에서 도메인 예외로 번역되어 나간다")
    void shouldTranslateSpringDataIntegrityViolationIntoDomainException() {
        // given: DB가 uk_users_oauth 를 거부했다. Spring Data 가 던지는 것은 프레임워크 예외다.
        when(userMapper.toEntity(any(User.class))).thenReturn(UserEntity.builder().build());
        when(userRepository.save(any(UserEntity.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "Duplicate entry for key 'uk_users_oauth'"));

        // when & then: 애플리케이션 계층은 프레임워크 예외를 보지 않는다.
        assertThatThrownBy(() -> userPersistenceAdapter.save(newUser()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_users_oauth");
    }

    @Test
    @DisplayName("정상 저장은 번역 없이 그대로 통과한다")
    void shouldReturnSavedUserWhenNoConstraintViolation() {
        // given
        UserEntity entity = UserEntity.builder().id(1L).build();
        User expected = newUser();

        when(userMapper.toEntity(any(User.class))).thenReturn(entity);
        when(userRepository.save(entity)).thenReturn(entity);
        when(userMapper.toDomain(entity)).thenReturn(expected);

        // when
        User result = userPersistenceAdapter.save(expected);

        // then
        assertThat(result).isSameAs(expected);
    }

    private User newUser() {
        return new User(null, "123", OAuthProvider.KAKAO, "test@test.com", "닉네임", Role.USER, null, null);
    }
}