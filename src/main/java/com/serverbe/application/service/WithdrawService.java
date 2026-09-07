package com.serverbe.application.service;

import com.serverbe.application.port.out.oauth.OAuthClientPort;
import com.serverbe.application.port.in.oauth.WithdrawUseCase;
import com.serverbe.application.port.out.jpa.UserRepositoryPort;
import com.serverbe.application.service.helper.UserDataCleanupManager;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.domain.exception.user.UserErrorCode;
import com.serverbe.domain.exception.user.UserException;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * @author Duskafka
 * @responsibility 사용자의 회원 탈퇴 요청을 처리하며, 외부 소셜 연동 해제와 내부 데이터 파기를 조율합니다.
 * @implSpec {@link WithdrawUseCase}의 구현체이며, 외부 서비스와의 통신 결과에 따라 내부 데이터 삭제 여부를 결정합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService implements WithdrawUseCase {

    private final UserRepositoryPort userRepositoryPort;
    private final Map<OAuthProvider, OAuthClientPort> oAuthClients;
    private final UserDataCleanupManager userDataCleanupManager;

    /**
     * @param userId 탈퇴할 사용자의 식별자
     * @return 탈퇴 완료 여부 (성공 시 {@code true})
     * @responsibility 소셜 연동 해제(Unlink)를 수행하고, 성공 시 시스템 내 사용자의 모든 데이터를 삭제(Hard Delete)합니다.
     * @requirement <b>UC-AUTH-03: 회원 탈퇴</b>
     * @implSpec 1. <b>데이터 정합성</b>: 외부 플랫폼(OAuth) 연동 해제가 성공한 경우에만 내부 데이터 삭제를 진행합니다.<br>
     * 2. <b>스레드 관리</b>: 블로킹 I/O 작업(JPA)을 수행하기 전 {@link Schedulers#boundedElastic()}을 통해 실행 컨텍스트를 전환합니다.<br>
     * 3. <b>트랜잭션 경계</b>: 이 메서드에는 선언적 트랜잭션({@code @Transactional})을 두지 않습니다.
     * 경계는 {@link UserDataCleanupManager#deleteAllUserData}에 있으며, 실제 삭제가 실행되는 스레드에서 열립니다.
     * @implNote 스레드에 바인딩되는 선언적 트랜잭션은 리액티브 파이프라인 위에서 무효합니다 — 프록시는 조립된
     * {@link Mono}가 리턴되는 순간 커밋하고, 실제 DB 작업은 그 뒤 {@link Schedulers#boundedElastic()} 스레드에서
     * 일어나기 때문입니다. 이 흐름 안에서 새 트랜잭션이 필요해지면
     * {@link AiGenerationService}처럼 {@code TransactionTemplate}을 실행 스레드 안에서 써야 합니다.
     */
    @Override
    public Mono<Boolean> withdraw(Long userId) {
        return Mono.fromCallable(() -> userRepositoryPort.findById(userId)
                        .orElseThrow(() -> new UserException(UserErrorCode.NOT_FOUND_USER)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(user -> {
                    OAuthClientPort client = getClient(user.provider());

                    // 1. 외부 소셜 서비스 연동 해제 (Unlink)
                    return client.unlink(user.provider(), user.oauthId(), user.oauthRefreshToken())
                            .publishOn(Schedulers.boundedElastic()) // 블로킹 삭제 작업을 위한 스케줄러 전환
                            .map(isUnlink -> {
                                if (Boolean.TRUE.equals(isUnlink)) {
                                    // 2. 내부 데이터 파기 (트랜잭션 경계는 이 헬퍼가 연다)
                                    userDataCleanupManager.deleteAllUserData(userId);

                                    log.info("[WITHDRAW] 사용자 데이터 파기 완료. 사용자 ID: {}, 소셜 제공자: {}", userId, user.provider());
                                    return true;
                                }

                                log.warn("[WITHDRAW FAIL] 소셜 연동 해제 거부 또는 실패. 사용자 ID: {}", userId);
                                return false;
                            });
                });
    }

    /**
     * @responsibility 요청된 {@link OAuthProvider}를 담당하는 {@link OAuthClientPort} 구현체를 조회표에서 꺼냅니다.
     * @implNote 조회표는 기동 시점에 {@code infrastructure.config.OAuthClientConfig}가 조립합니다.
     * 탈퇴는 이미 가입되어 있던 사용자의 {@code provider}로 조회하므로, 여기서 {@code null}이 나오면
     * 가입 이후 해당 어댑터가 사라졌다는 뜻입니다 — 로그를 {@code SECURITY/CONFIG ERROR}로 남기는 이유입니다.
     */
    private OAuthClientPort getClient(OAuthProvider provider) {
        OAuthClientPort client = oAuthClients.get(provider);
        if (client == null) {
            log.warn("[SECURITY/CONFIG ERROR] 지원하지 않는 소셜 로그인 방식 요청: {}", provider);
            throw new AuthException(AuthErrorCode.UNSUPPORTED_SOCIAL_LOGIN);
        }
        return client;
    }
}