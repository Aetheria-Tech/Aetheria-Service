package com.serverbe.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param host      Redis 서버 호스트 주소
 * @param port      Redis 서버 포트 번호
 * @param auth      리프레시 토큰(RT) 저장 및 인증 관련 설정 {@link Auth}
 * @param blacklist 로그아웃된 토큰(Blacklist) 관리 설정 {@link Blacklist}
 * @responsibility <b>Redis</b> 데이터베이스 연결 및 키 관리 전략(Auth, Blacklist)을 정의하는 프로퍼티 객체입니다.
 * @implSpec 설정 파일(application.yml)에서 <b>redis</b> 접두사로 시작하는 설정값을 계층 구조로 바인딩합니다.
 */
@ConfigurationProperties(prefix = "redis")
public record RedisProperties(
        String host,
        int port,
        Auth auth,
        Session session,
        Blacklist blacklist,
        Shedlock shedlock
) {
    /**
     * @param prefix   키 생성 시 식별을 위해 앞에 붙이는 접두사 (예: <b>RT:</b>)
     * @param suffix   키 뒤에 붙이는 추가 식별자
     * @param maxToken 한 유저가 동시 보유 가능한 최대 리프레시 토큰 개수
     * @responsibility <b>리프레시 토큰</b>의 Redis 키 생성 규칙 및 사용자당 최대 토큰 보유량을 관리합니다.
     */
    public record Auth(
            String prefix,
            String suffix,
            int maxToken
    ) {
    }

    /**
     * @param suffix Session의 접미사
     */
    public record Session(
            String suffix
    ) {
    }

    /**
     * @param accessTokenPrefix  액세스 토큰 블랙리스트에 사용되는 접두사
     * @param refreshTokenPrefix 리프레시 토큰 블랙리스트에 사용되는 접두사
     */
    public record Blacklist(
            String accessTokenPrefix,
            String refreshTokenPrefix
    ) {
    }

    public record Shedlock(
            String prefix
    ) {
    }
}