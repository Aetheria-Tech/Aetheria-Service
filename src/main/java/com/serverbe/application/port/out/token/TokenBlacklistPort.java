package com.serverbe.application.port.out.token;

import java.time.Duration;

/**
 * @responsibility 이미 죽은 토큰을 만료 시각까지 붙잡아 두어 재사용을 차단합니다.
 * @implSpec 이 포트는 <b>토큰 문자열 자체로 찾습니다.</b> 사용자가 누구인지, 어느 기기인지 모르고
 * 알 필요도 없습니다. 묻는 질문은 하나뿐입니다 — "이 토큰이 죽었는가".
 * @implNote 수명 관리를 저장소에 맡깁니다. 등록할 때 남은 시간을 TTL로 주면 만료와 동시에 사라지므로
 * 청소하는 코드가 필요 없습니다. 세션 관리는 {@link RefreshTokenSessionPort}를 보세요.
 */
public interface TokenBlacklistPort {

    /**
     * @param accessToken   블랙리스트에 등록할 액세스 토큰
     * @param remainingTime 토큰의 남은 시간(이는 곧 블랙리스트로 저장될 시간을 의미합니다.)
     * @responsibility 액세스 토큰을 블랙리스트 처리합니다.
     */
    void blacklistAccessToken(String accessToken, Duration remainingTime);

    /**
     * @param refreshToken  블랙리스트에 등록할 리프레시 토큰
     * @param remainingTime 토큰의 남은 시간(이는 곧 블랙리스트로 저장될 시간을 의미합니다.)
     * @responsibility 리프레시 토큰을 블랙리스트 처리합니다.
     */
    void blacklistRefreshToken(String refreshToken, Duration remainingTime);

    /**
     * @param accessToken 블랙리스트에 등록되었는지 확인할 액세스 토큰
     * @return 블랙리스트 되었다면 true, 아니면 false
     * @responsibility 액세스 토큰이 블랙리스트에 올라 있는지 확인합니다.
     */
    boolean isAccessTokenBlacklisted(String accessToken);

    /**
     * @param refreshToken 블랙리스트에 등록되었는지 확인할 리프레시 토큰
     * @return 블랙리스트 되었다면 true, 아니면 false
     * @responsibility 리프레시 토큰이 블랙리스트에 올라 있는지 확인합니다.
     */
    boolean isRefreshTokenBlacklisted(String refreshToken);
}
