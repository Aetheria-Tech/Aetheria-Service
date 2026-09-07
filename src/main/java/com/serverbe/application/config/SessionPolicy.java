package com.serverbe.application.config;

import java.time.Duration;

/**
 * @param refreshTokenTtl 리프레시 토큰의 수명. 로그아웃 시 블랙리스트 TTL의 기본값으로도 쓰인다.
 * @responsibility 로그인 세션의 수명 정책을 정의합니다.
 */
public record SessionPolicy(Duration refreshTokenTtl) {
}
