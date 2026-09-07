package com.serverbe.application.port.out.ratelimit;

/**
 * @responsibility 토큰 버킷 기반 처리율 제한 판정을 위임하는 아웃바운드 포트입니다.
 * @implSpec 구현체는 판정을 <b>원자적으로</b> 수행해야 합니다. 조회 후 갱신을 두 번의 왕복으로
 * 나누면 동시 요청이 같은 잔량을 읽어 한도를 넘깁니다.
 */
public interface RateLimitPort {

    /**
     * @param scope      제한 대상의 종류. 저장소 장애 시 폴백 정책을 고르는 근거가 된다.
     * @param key        버킷 키 (예: {@code rate:user:1:/api/v1/running-arts})
     * @param capacity   버킷 용량
     * @param refillRate 리필 속도
     * @return 허용이면 true, 차단이면 false
     */
    boolean isAllowed(RateLimitScope scope, String key, int capacity, int refillRate);
}
