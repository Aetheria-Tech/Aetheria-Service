package com.serverbe.application.port.out.oauth;

import com.serverbe.application.port.out.dto.oauth.OAuthUserInfoResult;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import reactor.core.publisher.Mono;

/**
 * @responsibility 외부 OAuth 제공자(Kakao, Google 등)와 통신하여 사용자 인증 및 계정 관리 기능을 수행하는 아웃바운드 포트 인터페이스입니다.
 * 외부 소셜 서비스와의 복잡한 인증 프로토콜을 추상화하여 도메인 계층에 일관된 인터페이스를 제공합니다.
 */
public interface OAuthClientPort {

    /**
     * @responsibility 소셜 플랫폼으로부터 받은 인가 코드(Authorization Code)를 사용하여 사용자의 프로필 정보를 획득합니다.
     * @param code OAuth 인증 서버에서 발급받은 인가 코드
     * @param provider 인증을 수행할 소셜 제공자 {@link OAuthProvider}
     * @return 획득한 사용자 정보를 포함하는 비동기 결과 {@link Mono<OAuthUserInfoResult>}
     */
    Mono<OAuthUserInfoResult> getUserInfo(String code, OAuthProvider provider);

    /**
     * @responsibility 사용자의 소셜 계정과 우리 앱 간의 연동을 해제(Unlink)하여 회원 탈퇴 등의 프로세스를 처리합니다.
     * @param provider 연동을 해제할 소셜 제공자 {@link OAuthProvider}
     * @param oauthId 소셜 플랫폼에서의 사용자 고유 ID
     * @param oauthRefreshToken 연동 해제에 필요한 소셜 리프레시 토큰 (Google 등 특정 플랫폼에서 요구됨)
     * @return 연동 해제 성공 시 true, 실패 시 false를 담은 {@link Mono<Boolean>}
     */
    Mono<Boolean> unlink(OAuthProvider provider, String oauthId, String oauthRefreshToken);

    /**
     * @responsibility 이 어댑터가 담당하는 소셜 제공자를 <b>선언</b>합니다.
     * @return 이 구현체가 책임지는 소셜 제공자 {@link OAuthProvider}
     * @implSpec 구현체 하나가 제공자 하나를 담당합니다. 이 값이 곧 디스패치 키이므로, 두 구현체가
     * 같은 값을 선언하면 {@code infrastructure.config.OAuthClientConfig}에서 <b>기동이 실패합니다.</b>
     * @implNote 이전에는 {@code boolean supports(OAuthProvider)}였습니다. 그것은 <b>질의</b>라
     * 호출자가 후보를 하나씩 들고 물어봐야 했고, 그래서 서비스마다 리스트를 순회하는 선택 코드가
     * 생겼습니다. <b>선언</b>은 키가 되므로 조립이 기동 시점에 한 번으로 끝납니다 — 포트가
     * "나를 고르는 방법"까지 계약에 적을 필요는 없습니다.
     */
    OAuthProvider provider();

    /**
     * @responsibility 사용자를 인증하기 위한 소셜 플랫폼별 로그인 페이지(Authorization Endpoint) URL을 생성하여 반환합니다.
     * @return 사용자를 리다이렉트시킬 소셜 로그인 URL {@link String}
     */
    String getLoginUrl();
}