package com.serverbe.application.port.out.ratelimit;

/**
 * @responsibility 처리율 제한 대상의 종류를 나타냅니다.
 * @implSpec 어댑터는 Redis 장애 시 어떤 폴백 정책을 적용할지 이 값으로 판단합니다.
 * {@link #USER}는 로컬 캐시로 세어 한도 안에서만 허용하고, {@link #IP}는 무조건 허용합니다.
 * @implNote 키 접두사를 어댑터에서 파싱해 종류를 알아내는 방법도 있지만, 그러면 키 이름 규칙이라는
 * 애플리케이션 관심사가 어댑터로 새어 들어갑니다. 종류를 계약에 명시하는 편이 정직합니다.
 */
public enum RateLimitScope {

    /** 인증된 사용자 단위 제한. */
    USER,

    /** 클라이언트 IP 단위 제한. */
    IP
}
