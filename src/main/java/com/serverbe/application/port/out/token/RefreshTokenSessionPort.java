package com.serverbe.application.port.out.token;

import java.time.Duration;
import java.util.Set;

/**
 * @responsibility 멀티 디바이스 환경의 <b>리프레시 토큰 세션</b>을 관리합니다.
 * @implSpec 이 포트의 모든 메서드는 <b>{@code userId + deviceId}로 세션을 찾습니다.</b> 키의 주인이
 * 사용자이므로 "1인 N기기" 정책 — 세션 개수 세기, 가장 오래된 세션 축출, 전역 로그아웃 — 이 전부
 * 여기 삽니다.
 * @implNote 토큰 문자열 자체를 키로 삼는 무효화는 {@link TokenBlacklistPort}의 몫입니다.
 * 둘은 같은 저장소를 쓸 뿐 데이터 모델도 수명 정책도 다릅니다. 유일하게 두 갈래가 만나는 지점은
 * {@link #rotateRefreshToken}으로, 구 토큰 무효화와 신 토큰 발급을 한 번에 처리해야 하기 때문입니다.
 */
public interface RefreshTokenSessionPort {

    /**
     * @param userId       사용자 식별자
     * @param deviceId     기기 식별자 (모바일, PC 등)
     * @param refreshToken 발급된 리프레시 토큰
     * @param expiry       토큰 유효 기간
     * @responsibility 특정 기기의 리프레시 토큰을 저장하고, 해당 사용자의 세션 목록(Index)을 업데이트합니다.
     */
    void saveRefreshToken(Long userId, String deviceId, String refreshToken, Duration expiry);

    /**
     * @param userId   리프레시 토큰 조회를 요청한 사용자의 식별자
     * @param deviceId 리프레시 토큰 조회를 요청한 기기의 식별자
     * @responsibility 특정 기기에 할당된 리프레시 토큰을 조회합니다.
     */
    String getRefreshToken(Long userId, String deviceId);

    /**
     * @param userId   리프레시 토큰 삭제를 요청한 사용자의 식별자
     * @param deviceId 리프레시 토큰 삭제를 요청한 기기의 식별자
     * @responsibility 특정 기기의 세션만 로그아웃 처리합니다.
     */
    void deleteRefreshToken(Long userId, String deviceId);

    /**
     * @param userId 모든 리프레시 토큰 삭제를 요청한 사용자의 식별자
     * @responsibility 해당 사용자의 모든 기기에서 로그아웃 처리합니다. (전체 세션 비활성화)
     */
    void deleteAllRefreshTokens(Long userId);

    /**
     * @responsibility 특정 사용자의 현재 활성화된 모든 기기 식별자(deviceId) 목록을 조회합니다.
     * 가장 오래된 세션을 찾거나 세션 개수를 확인할 때 사용합니다.
     */
    Set<String> getAllDeviceIds(Long userId);

    /**
     * @param userId 사용자 식별자
     * @responsibility 사용자가 보유한 세션 중 가장 오래된(점수가 가장 낮은) 세션을 삭제합니다.
     */
    void removeOldestSession(Long userId);

    /**
     * @responsibility 현재 활성 세션 개수를 반환합니다.
     */
    long getSessionCount(Long userId);

    /**
     * @responsibility 신규 토큰 저장과 기존 토큰 블랙리스트 등록을 하나의 원자적 작업으로 수행합니다.
     * @implNote <b>이 메서드만 두 갈래에 걸칩니다.</b> 구 토큰의 블랙리스트 등록을 애플리케이션이
     * 따로 호출하면 그 사이에 프로세스가 죽었을 때 어중간한 상태가 남고, 그 상태가 전부 보안
     * 사고입니다. 그래서 무효화가 세션 쪽 계약 안에 있습니다.
     */
    void rotateRefreshToken(Long userId, String deviceId, String oldRefreshToken, String newRefreshToken, Duration expiry);

    /**
     * @responsibility 특정 기기의 리프레시 토큰이 저장소에 존재하고 일치하는지 검증합니다.
     */
    boolean existsRefreshToken(Long userId, String deviceId, String refreshToken);

    /**
     * @responsibility 특정 기기 세션의 남은 수명(밀리초)을 반환합니다. 만료되었거나 없으면 0입니다.
     */
    long getSessionTtl(Long userId, String deviceId);
}
