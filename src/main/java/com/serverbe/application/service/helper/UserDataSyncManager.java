package com.serverbe.application.service.helper;

import com.serverbe.application.port.out.dto.oauth.OAuthUserInfoResult;
import com.serverbe.application.port.out.jpa.UserRepositoryPort;
import com.serverbe.domain.exception.server.DataIntegrityViolationException;
import com.serverbe.domain.model.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Slf4j
public class UserDataSyncManager {

    private final UserRepositoryPort userRepositoryPort;

    /**
     * 신규 가입 INSERT 전용 템플릿.
     * <p>
     * 바깥 트랜잭션에 합류시키면 {@code uk_users_oauth} 위반 시 그 트랜잭션까지 rollback-only 로
     * 오염되어 뒤이은 재조회 자체가 불가능해집니다. {@code REQUIRES_NEW} 로 분리해 두면
     * 실패가 안쪽 트랜잭션에만 갇히고 바깥은 그대로 살아남아 복구 조회를 수행할 수 있습니다.
     * </p>
     */
    private final TransactionTemplate registrationTransactionTemplate;

    public UserDataSyncManager(
            UserRepositoryPort userRepositoryPort,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepositoryPort = userRepositoryPort;

        this.registrationTransactionTemplate = new TransactionTemplate(transactionManager);
        this.registrationTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @responsibility OAuth 정보를 바탕으로 기존 유저 정보를 갱신하거나 신규 유저를 생성합니다. (Upsert)
     */
    @Transactional
    public User syncUserByOAuth(OAuthUserInfoResult oauthInfo) {
        return userRepositoryPort.findByOauthId(oauthInfo.oauthId(), oauthInfo.provider())
                .map(existingUser -> {
                    log.info("[LOGIN] 기존 회원 접속: ID={}, Provider={}", existingUser.id(), oauthInfo.provider());
                    return refresh(existingUser, oauthInfo);
                })
                .orElseGet(() -> registerOrRecover(oauthInfo));
    }

    /**
     * 신규 회원을 등록하되, 동시 최초 로그인 경합으로 유니크 제약에 걸리면 재조회로 복구합니다.
     * <p>
     * "조회 후 없으면 삽입"은 두 요청이 동시에 조회 단계를 통과할 수 있어 그 자체로는 중복을 막지 못합니다.
     * 중복을 실제로 거부하는 것은 {@code users(oauth_id, oauth_provider)} 유니크 인덱스이며,
     * 경합에서 진 쪽은 여기서 예외를 받습니다. 그 시점에는 이긴 쪽의 행이 이미 커밋되어 있으므로
     * 다시 조회하면 반드시 찾을 수 있고, 사용자는 500 대신 정상 로그인을 받습니다.
     * </p>
     * <p>
     * 여기서 받는 {@link DataIntegrityViolationException}은 <b>도메인 예외</b>입니다. DB가 제약 위반을
     * 알려 주는 방식(스프링의 같은 이름 예외)은 영속성 어댑터가 이미 번역해 주었고, 이 계층은 "무결성
     * 위반이 있었다"까지만 받습니다. <b>그것을 "동시 최초 로그인 경합"으로 해석하는 것이 여기의 판단</b>이고,
     * 재조회에 실패하면 해석이 틀렸다는 뜻이므로 삼키지 않고 그대로 전파합니다.
     * </p>
     */
    private User registerOrRecover(OAuthUserInfoResult oauthInfo) {
        try {
            User newUser = registrationTransactionTemplate.execute(status ->
                    userRepositoryPort.save(User.createNew(
                            oauthInfo.oauthId(),
                            oauthInfo.provider(),
                            oauthInfo.email(),
                            oauthInfo.nickname(),
                            oauthInfo.oauthRefreshToken()
                    ))
            );
            log.info("[REGISTER] 신규 회원 가입 완료: ID={}, Provider={}", newUser.id(), oauthInfo.provider());
            return newUser;
        } catch (DataIntegrityViolationException e) {
            log.warn("[REGISTER] 동시 최초 로그인 경합 감지, 기존 회원으로 복구합니다: Provider={}", oauthInfo.provider());

            return userRepositoryPort.findByOauthId(oauthInfo.oauthId(), oauthInfo.provider())
                    .map(existingUser -> refresh(existingUser, oauthInfo))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * OAuth 제공자가 준 최신 프로필과 리프레시 토큰으로 기존 회원 정보를 갱신합니다.
     */
    private User refresh(User existingUser, OAuthUserInfoResult oauthInfo) {
        return userRepositoryPort.save(existingUser.updateFromOAuth(
                oauthInfo.email(), oauthInfo.nickname(), oauthInfo.oauthRefreshToken()
        ));
    }
}
