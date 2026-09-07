package com.serverbe.adapter.out.persistence.user;

import com.serverbe.adapter.out.persistence.mapper.UserMapper;
import com.serverbe.application.port.out.jpa.UserRepositoryPort;
import com.serverbe.domain.exception.server.DataIntegrityViolationException;
import com.serverbe.domain.exception.server.ServerErrorCode;
import com.serverbe.domain.model.user.User;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import com.serverbe.infrastructure.crypto.EncryptionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * @author Duskafka
 * @responsibility 유저(User) 도메인의 영속성을 관리하며, 데이터 조회 시 암호화 버전 관리 및 자동 마이그레이션을 담당하는 아웃바운드 어댑터입니다.
 * @implSpec Spring Data JPA를 사용하며, JPA AttributeConverter와 연동된 {@link EncryptionContext}를 통해 투명한 데이터 암호화를 지원합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserPersistenceAdapter implements UserRepositoryPort {

    private final JpaUserRepository jpaUserRepository;
    private final UserMapper userMapper;

    /**
     * @param oauthId  OAuth 서비스에서 제공하는 고유 식별자
     * @param provider OAuth 서비스 제공자 (예: KAKAO, GOOGLE) {@link OAuthProvider}
     * @return 조회 및 마이그레이션 처리가 완료된 유저 정보를 담은 {@link Optional<User>}
     * @responsibility OAuth 제공자 정보와 ID를 기반으로 유저를 조회하며, 필요 시 암호화 데이터를 최신 버전으로 갱신합니다.
     */
    @Override
    public Optional<User> findByOauthId(String oauthId, OAuthProvider provider) {
        return jpaUserRepository.findByOauthIdAndProvider(oauthId, provider)
                .map(this::mapToDomainWithMigration);
    }

    /**
     * @param id 유저의 고유 식별자
     * @return 조회된 유저 정보를 포함하는 {@link Optional<User>}
     * @responsibility 시스템 고유 ID를 통해 유저를 조회하며, 암호화 버전이 낮을 경우 마이그레이션을 수행합니다.
     */
    @Override
    public Optional<User> findById(Long id) {
        return jpaUserRepository.findById(id)
                .map(this::mapToDomainWithMigration);
    }

    /**
     * @param user 저장할 {@link User} 도메인 모델
     * @return 저장 및 식별자 할당이 완료된 {@link User} 도메인 모델
     * @responsibility 유저 도메인 모델을 영구 저장소에 저장하고, DB 제약 위반을 도메인 예외로 <b>번역</b>합니다.
     * @implSpec 엔티티로 변환되는 과정에서 JPA AttributeConverter(CryptoConverter)에 의해 민감 정보가 자동 암호화됩니다.
     * @implNote 유니크 제약 위반을 도메인 언어로 옮기는 것은 <b>영속성 어댑터의 몫</b>입니다. 스프링의
     * {@code DataIntegrityViolationException}이 애플리케이션 계층까지 올라가면 그 계층이 프레임워크를 알게 되고,
     * 도메인에 이름이 같은 예외가 있어 <b>어느 쪽인지 import를 봐야만 알 수 있게</b> 됩니다.
     * @implNote {@link UserEntity}의 식별자가 {@code IDENTITY} 전략이라 {@code persist} 시점에 INSERT가 즉시
     * 실행됩니다. 덕분에 제약 위반이 커밋까지 미뤄지지 않고 <b>이 메서드 안에서</b> 잡힙니다.
     * @implNote 스프링 예외를 import하지 않고 catch 절에 패키지 전체 이름을 쓰는 것은
     * {@code AiTaskPersistenceAdapter}와 같은 관용구입니다. 도메인 예외와 이름이 같아 짧게 쓸 수 없습니다.
     */
    @Override
    public User save(User user) {
        UserEntity entity = userMapper.toEntity(user);
        try {
            UserEntity savedEntity = jpaUserRepository.save(entity);
            return userMapper.toDomain(savedEntity);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 어댑터는 "DB가 무결성 이유로 거부했다"까지만 말합니다. 그것이 동시 최초 로그인 경합인지는
            // 재조회에 성공해 봐야 알 수 있고, 그 판단은 호출자(UserDataSyncManager)의 몫입니다.
            log.warn("[Integrity] 사용자 저장이 DB 무결성 제약에서 거부되었습니다.", e);
            throw new DataIntegrityViolationException(
                    ServerErrorCode.INTERNAL_SERVER_ERROR, e.getMostSpecificCause().getMessage());
        }
    }

    /**
     * @param email 중복 체크를 진행할 이메일 주소
     * @return 가입된 이메일이 존재하면 true, 아니면 false
     * @responsibility 특정 이메일 주소의 가입 여부를 확인합니다.
     */
    @Override
    public boolean existsByEmail(String email) {
        return jpaUserRepository.existsByEmail(email);
    }

    /**
     * @param id 삭제할 유저의 고유 식별자
     * @responsibility 식별자를 기준으로 유저 데이터를 삭제합니다.
     */
    @Override
    public void deleteById(Long id) {
        jpaUserRepository.deleteById(id);
    }

    /**
     * @param entity 변환할 {@link UserEntity}
     * @return 도메인 모델로 변환된 {@link User}
     * @responsibility 엔티티를 도메인 객체로 변환하며, 암호화 버전이 낮은 경우 최신 버전으로 마이그레이션을 수행합니다.
     * @implSpec {@link EncryptionContext}의 ThreadLocal 플래그를 확인하여 마이그레이션 대상을 판별합니다.
     * @implNote 1. {@code needsMigration} 스냅샷을 통해 매핑 로직 중 발생할 수 있는 Side-effect로부터 판단 로직을 보호합니다.<br>
     * 2. 마이그레이션이 필요한 경우 {@code forceUpdate()}와 {@code saveAndFlush()}를 호출하여 DB 컨버터가 새 키로 다시 암호화하도록 강제합니다.
     */
    private User mapToDomainWithMigration(UserEntity entity) {
        // 1. 매퍼 실행 전, 컨버터에 의해 세팅된 플래그를 로컬 변수에 즉시 저장(Snapshot)
        boolean needsMigration = EncryptionContext.isMigrationRequired();
        if (needsMigration) {
            // 플래그를 읽은 후 즉시 컨텍스트를 초기화하여, 동일 스레드의 다른 작업에 영향을 주지 않도록 합니다.
            EncryptionContext.clear();
        }

        // 2. 도메인 변환 실행
        User domainUser = userMapper.toDomain(entity);

        // 3. 미리 담아둔 스냅샷 변수로 마이그레이션 여부 판단
        if (needsMigration) {
            log.debug("[Adapter] 암호화 마이그레이션 실행. User ID: {}", entity.getId());
            entity.forceUpdate();
            jpaUserRepository.saveAndFlush(entity);
        }

        return domainUser;
    }
}