package com.serverbe.domain.model.user;

import com.serverbe.domain.model.user.vo.OAuthProvider;
import com.serverbe.domain.model.user.vo.Role;

import java.util.List;

/**
 * @responsibility 시스템의 핵심 <b>유저 엔티티</b>를 나타내는 불변 객체입니다.
 * @param id 시스템 내부 식별자 (PK)
 * @param oauthId 소셜 서비스에서 제공하는 고유 식별자
 * @param provider 소셜 로그인 제공자 {@link OAuthProvider}
 * @param email 사용자 이메일
 * @param nickname 사용자 닉네임
 * @param role 사용자 권한 {@link Role}
 * @param statusMessage 사용자의 상태 메시지
 * @param oauthRefreshToken 외부 소셜 서비스의 리프레시 토큰
 */
public record User(
        Long id,
        String oauthId,
        OAuthProvider provider,
        String email,
        String nickname,
        Role role,
        String statusMessage,
        String oauthRefreshToken
) {
    /**
     * @responsibility Spring Security 등 인증 프레임워크와의 호환을 위해 권한 리스트를 반환합니다.
     * @return 사용자의 권한 명칭 리스트
     */
    public List<String> getRoleList() {
        return List.of(role.name());
    }

    /**
     * @responsibility <b>신규 가입</b>을 위한 유저 객체를 생성하는 정적 팩토리 메서드입니다.
     * @param oauthId 소셜 서비스에서 발급한 사용자의 고유 식별자
     * @param provider 소셜 로그인 제공자
     * @param email 사용자 이메일
     * @param nickname 사용자 닉네임
     * @param oauthRefreshToken 외부 소셜 서비스의 리프레시 토큰
     * @return 기본 권한(USER)이 부여된 신규 {@link User} 객체
     * @implNote 도메인 계층이 애플리케이션 계층의 DTO(예: OAuth 응답 객체)에 의존하지 않도록,
     * 원시 값들을 개별 파라미터로 전달받습니다. 호출자(애플리케이션 서비스)가 외부 응답 객체에서
     * 필요한 값을 추출해 전달할 책임을 집니다.
     */
    public static User createNew(String oauthId, OAuthProvider provider, String email, String nickname, String oauthRefreshToken) {
        return new User(
                null,
                oauthId,
                provider,
                email,
                nickname,
                Role.USER,
                null,
                oauthRefreshToken
        );
    }

    /**
     * @responsibility 외부 소셜 서비스의 최신 정보를 반영하여 유저 객체를 업데이트합니다.
     * @param email 갱신할 사용자 이메일
     * @param nickname 갱신할 사용자 닉네임
     * @param oauthRefreshToken 갱신할 외부 소셜 서비스의 리프레시 토큰
     * @return 정보가 동기화된 새로운 {@link User} 객체
     */
    public User updateFromOAuth(String email, String nickname, String oauthRefreshToken) {
        return new User(
                this.id,
                this.oauthId,
                this.provider,
                email,
                nickname,
                this.role,
                this.statusMessage,
                oauthRefreshToken
        );
    }

    /**
     * @responsibility 사용자의 프로필 정보를 수정합니다.
     * @param nickname 변경할 닉네임
     * @param statusMessage 변경할 상태 메시지
     * @return 프로필 정보가 수정된 새로운 {@link User} 객체
     */
    public User updateProfile(String nickname, String statusMessage) {
        return new User(
                this.id,
                this.oauthId,
                this.provider,
                this.email,
                nickname,
                this.role,
                statusMessage,
                this.oauthRefreshToken
        );
    }
}