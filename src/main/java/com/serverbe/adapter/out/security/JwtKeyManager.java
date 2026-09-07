package com.serverbe.adapter.out.security;

import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.infrastructure.config.properties.JwtProperties;
import com.serverbe.domain.exception.BusinessException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;

/**
 * @responsibility JWT(JSON Web Token)의 서명 검증에 필요한 <b>비밀 키(SecretKey)</b>를 안전하게 관리하고, 토큰 해석을 위한 <b>{@link JwtParser}</b>를 제공합니다.
 * @implSpec 1. 애플리케이션 기동 시 {@link JwtProperties}에서 보안 키를 로드하여 메모리에 캐싱합니다.<br>
 * 2. <b>HS256</b> 알고리즘 표준에 따른 최소 키 길이를 검증하여 보안 취약점을 방어합니다.<br>
 * 3. 서버 간 시간 오차를 허용하는 <b>Clock Skew</b> 설정을 파서에 적용합니다.
 */
@Getter
@Component
public class JwtKeyManager {
    /**
     * JWT 서명 및 유효성 검증에 사용되는 암호화 키
     */
    private final SecretKey key;
    /**
     * 구성이 완료된 재사용 가능한 JWT 파서
     */
    private final JwtParser parser;

    /**
     * @param jwtProperties JWT 보안 설정 정보 {@link JwtProperties}
     * @throws BusinessException 키 형식이 잘못되었거나 보안 요구사항(32바이트 미만)을 충족하지 못할 경우 발생
     * @responsibility 설정 프로퍼티를 기반으로 암호화 키를 생성하고 파서를 초기화합니다.
     * @implNote 1. <b>디코딩</b>: 설정 파일의 문자열을 Base64 형식으로 디코딩하여 바이트 배열을 추출합니다.<br>
     * 2. <b>보안 검증</b>: HS256 알고리즘은 최소 256비트(32바이트) 이상의 키를 요구하며, 미달 시 시스템 기동을 차단합니다.<br>
     * 3. <b>파서 설정</b>: 서명 키와 더불어 분산 환경에서의 시간 동기화 오차를 보정하기 위해 {@code setAllowedClockSkewSeconds}를 설정합니다.
     */
    public JwtKeyManager(JwtProperties jwtProperties) {
        final byte[] keyBytes;
        try {
            // Base64 인코딩된 비밀 키 문자열을 바이트 배열로 디코딩
            keyBytes = Decoders.BASE64.decode(jwtProperties.secret());
        } catch (IllegalArgumentException e) {
            throw new AuthException(
                    AuthErrorCode.JWT_KEY_INVALID,
                    "jwt.secret은 Base64 인코딩된 문자열이어야 합니다."
            );
        }

        // HS256 알고리즘의 최소 권장 길이인 256비트(32바이트) 검증
        if (keyBytes.length < 32) {
            throw new AuthException(
                    AuthErrorCode.JWT_KEY_INVALID,
                    "jwt.secret은 HS256에 적합한 최소 256비트(32바이트) 이상이어야 합니다."
            );
        }

        // HMAC SHA 키 생성
        this.key = Keys.hmacShaKeyFor(keyBytes);

        // JwtParser 초기화 및 설정 (서명 키 및 허용 오차 시간 설정)
        this.parser = Jwts.parserBuilder()
                .setSigningKey(key)
                .setAllowedClockSkewSeconds(jwtProperties.allowedClockSkewSeconds())
                .build();
    }
}