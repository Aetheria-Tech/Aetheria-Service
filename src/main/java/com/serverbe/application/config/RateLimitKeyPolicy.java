package com.serverbe.application.config;

/**
 * @param userPrefix 사용자 단위 버킷 키의 접두사
 * @param ipPrefix   IP 단위 버킷 키의 접두사
 * @responsibility 처리율 제한 버킷 키의 이름 규칙을 정의합니다.
 * @implNote 접두사가 바뀌면 기존 버킷과 분리되어 순간적으로 제한이 풀립니다. 값을 바꿀 때 주의하세요.
 */
public record RateLimitKeyPolicy(String userPrefix, String ipPrefix) {
}
