package com.serverbe.application.service;

import com.serverbe.application.port.in.oauth.LoginUseCase;
import com.serverbe.application.port.out.dto.oauth.TokenResult;
import com.serverbe.application.port.out.oauth.OAuthClientPort;
import com.serverbe.application.port.out.security.TokenProvider;
import com.serverbe.application.service.helper.AuthSessionManager;
import com.serverbe.application.service.helper.UserDataSyncManager;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.domain.model.user.User;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import com.serverbe.domain.model.user.vo.Role;
import com.serverbe.domain.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * @author Duskafka
 * @responsibility 외부 소셜 인증 결과를 시스템 내부 사용자 정보와 동기화하고, 보안 세션을 생성하는 총괄 지휘자 역할을 수행합니다.
 * @implSpec 1. <b>제공자별 디스패치</b>: {@link OAuthClientPort} 구현체를 제공자로 찾아 다중 소셜 플랫폼을
 * 지원합니다. 조회표는 기동 시점에 {@code infrastructure.config.OAuthClientConfig}가 조립하므로
 * 이 서비스는 <b>고르지 않고 꺼내 쓰기만 합니다.</b><br>
 * 2. <b>책임 분리</b>: DB 트랜잭션은 {@link UserDataSyncManager}에게, Redis 세션 관리는 {@link AuthSessionManager}에게 위임합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService implements LoginUseCase {

    private final Map<OAuthProvider, OAuthClientPort> oAuthClients;
    private final UserDataSyncManager userDataSyncManager;
    private final AuthSessionManager authSessionManager;
    private final TokenProvider tokenProvider;

    /**
     * @param code     소셜 플랫폼 인가 코드
     * @param deviceId 사용자의 기기 식별자
     * @param provider 인증 제공자
     * @return 발급된 토큰 세트를 포함한 {@link Mono}
     * @responsibility 소셜 인증 코드를 검증하여 사용자 정보를 획득하고, 가입/로그인 처리 후 최종 보안 토큰을 반환합니다.
     * @requirement <b>UC-AUTH-01: 소셜 로그인 및 회원가입</b>
     * @implNote 1. 외부 API 통신 후 {@link Schedulers#boundedElastic()}으로 전환하여 블로킹 I/O 작업을 안전하게 처리합니다.<br>
     * 2. <b>이 메서드에는 트랜잭션 경계가 없습니다.</b> 회원 동기화는
     * {@link UserDataSyncManager#syncUserByOAuth}가 여는 JPA 트랜잭션 안에서, 세션 저장은
     * {@link AuthSessionManager#saveSession}을 통해 Redis에서 각각 독립적으로 일어납니다.<br>
     * 3. 따라서 <b>둘 사이는 원자적이지 않습니다.</b> 세션 저장이 실패하면 회원 정보는 커밋된 채 로그인만
     * 실패합니다. 복구 경로는 <b>재로그인</b>입니다 — {@code syncUserByOAuth}가 멱등한 upsert라
     * 커밋된 사용자 행은 그대로 재사용되고, 저장되지 못한 리프레시 토큰은 Redis 어디에도 남지 않습니다.
     */
    @Override
    public Mono<TokenResult> login(String code, OAuthProvider provider, String deviceId) {
        OAuthClientPort client = getClient(provider);

        return client.getUserInfo(code, provider)
                .publishOn(Schedulers.boundedElastic())
                .map(oauthInfo -> {
                    log.debug("[OAUTH] 소셜 사용자 정보 수신 성공: OAuthID={}, Provider={}", oauthInfo.oauthId(), provider);

                    // 1. 유저 정보 동기화 (JPA 트랜잭션은 헬퍼가 연다)
                    User user = userDataSyncManager.syncUserByOAuth(oauthInfo);

                    // 2. 서비스 전용 토큰 생성 (JWT)
                    TokenResult newTokens = generateTokens(user.id(), user.role());

                    // 3. 보안 세션 저장 (Redis TTL 관리)
                    authSessionManager.saveSession(user.id(), deviceId, newTokens.refreshTokenResult().opaqueToken());

                    return newTokens;
                });
    }

    /**
     * @param userId 사용자 식별자
     * @param role   사용자 권한
     * @return 생성된 {@link TokenResult}
     * @responsibility 유저의 식별자와 권한을 기반으로 신규 Access/Refresh 토큰 쌍을 생성합니다.
     */
    private TokenResult generateTokens(Long userId, Role role) {
        return TokenResult.of(
                tokenProvider.generateAccessToken(userId, role),
                tokenProvider.generateRefreshToken(userId, role),
                role
        );
    }

    /**
     * @param provider 소셜 제공자
     * @return 리다이렉트 URL 문자열
     * @responsibility 소셜 플랫폼별 최적화된 인증 페이지 URL을 획득합니다.
     */
    @Override
    public String getSocialLoginUrl(OAuthProvider provider) {
        return getClient(provider).getLoginUrl();
    }

    /**
     * @throws BusinessException 지원하지 않는 플랫폼 요청 시 발생
     * @responsibility 제공된 {@link OAuthProvider}를 담당하는 클라이언트 어댑터를 조회표에서 꺼냅니다.
     * @implNote {@code null}이 나오는 경우는 남습니다 — 요청에 실린 문자열이 {@link OAuthProvider}로
     * 변환은 됐는데 그 제공자를 담당하는 어댑터가 아직 없는 경우입니다.
     */
    private OAuthClientPort getClient(OAuthProvider provider) {
        OAuthClientPort client = oAuthClients.get(provider);
        if (client == null) {
            log.warn("[SECURITY ALERT] 미지원 인증 요청: Provider={}", provider);
            throw new AuthException(AuthErrorCode.UNSUPPORTED_SOCIAL_LOGIN);
        }
        return client;
    }
}