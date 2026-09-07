package com.serverbe.adapter.out.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.application.port.out.crypto.EncryptPort;
import com.serverbe.application.port.out.security.TokenResolver;
import com.serverbe.application.port.out.security.dto.JwtPayloadDto;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.domain.model.user.vo.Role;
import com.serverbe.infrastructure.config.properties.JwtProperties;
import com.serverbe.domain.exception.BusinessException;
import io.jsonwebtoken.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @author Duskafka
 * @responsibility 발급된 JWT 토큰 및 리프레시 토큰의 유효성을 검증하고, 토큰 페이로드에서 사용자 식별자 및 권한 정보를 추출합니다.
 * @implSpec 1. <b>JwtParser</b>: {@link JwtKeyManager}에서 주입받은 공유 파서를 사용하여 서명을 검증합니다.<br>
 * 2. <b>예외 복구</b>: 토큰 재발급(Reissue) 로직을 지원하기 위해, 만료된 토큰({@link ExpiredJwtException})에서도 사용자 ID를 추출할 수 있는 특수 로직을 포함합니다.
 * @see TokenResolver
 */
@Slf4j
@Component
public class JwtTokenResolver implements TokenResolver {
    private final JwtParser parser;
    private final String roleKey;
    private final String idKey;

    // 빈으로 등록하는 것은 일반적인 패턴이 아니며, 이 경우 특별한 이점이 없습니다. 따라서 내부에서 관리할 수 있게한다.
    private final TypeReference<Map<String, Object>> typeReference = new TypeReference<>() {};
    private final EncryptPort encryptPort;
    private final ObjectMapper objectMapper;

    /**
     * @param jwtKeyManager 서명 키가 설정된 파서를 제공하는 매니저 {@link JwtKeyManager}
     * @param jwtProperties 토큰 사양(권한 키, 길이 등)을 담은 프로퍼티 {@link JwtProperties}
     * @responsibility 토큰 해석에 필요한 파서와 설정 정보를 주입받아 초기화합니다.
     */
    public JwtTokenResolver(
            JwtKeyManager jwtKeyManager,
            JwtProperties jwtProperties,
            EncryptPort encryptPort,
            ObjectMapper objectMapper
    ) {
        this.parser = jwtKeyManager.getParser();
        this.roleKey = jwtProperties.roleKey();
        this.idKey = jwtProperties.idKey();

        this.encryptPort = encryptPort;
        this.objectMapper = objectMapper;
    }

    /**
     * @param token 복호화할 JWT 액세스/리프레시 토큰
     * @return 사용자 ID와 권한 정보를 담은 {@link JwtPayloadDto}
     * @responsibility <b>[최적화 핵심]</b> 고비용 연산인 JWS 서명 검증, AES-GCM 복호화, JSON 파싱을
     * 단 1회만 수행하여 필요한 데이터를 모두 추출합니다.
     * @implNote 여기서 만든 페이로드로 Spring Security 인증 객체를 조립하는 일은
     * {@code adapter.in.web.filter.JwtAuthenticationFilter}가 합니다.
     */
    @Override
    public JwtPayloadDto resolvePayload(String token) {
        // 1. 여기서 무거운 파싱 및 복호화 연산이 딱 한 번 수행됨
        Map<String, Object> claimsMap = getDecryptedPayload(token);

        // 2. 이미 파싱된 Map 데이터를 재사용하여 ID와 Role을 추출
        return new JwtPayloadDto(
                extractId(claimsMap),
                extractRole(claimsMap)
        );
    }

    /**
     * @param accessToken 검증할 JWT 문자열
     * @return 유효성 여부 (유효하지 않거나 만료된 경우 false)
     * @responsibility <b>액세스 토큰(JWT)</b>의 서명 위변조 여부 및 구조적 무결성을 검증합니다.
     */
    @Override
    public boolean validateAccessToken(String accessToken) {
        try {
            if (!StringUtils.hasText(accessToken)) return false;
            parser.parseClaimsJws(accessToken); // 서명 확인
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[JWT Validation Failed] -> {}", e.getMessage());
            return false;
        }
    }

    /**
     * @param refreshToken 검증할 리프레시 토큰
     * @return 값이 있으면 true, 비어 있으면 false
     * @responsibility <b>리프레시 토큰(Opaque)</b>이 비어 있지 않은지만 확인합니다.
     * @implNote 리프레시 토큰은 정보를 담지 않는 무작위 문자열이라 <b>토큰 자체를 보고 판단할 수 있는
     * 것이 없습니다.</b> 서명도 만료 클레임도 없으므로 여기서 하는 일은 빈 값 거르기가 전부이고,
     * <b>실제 유효성은 저장소와의 대조로 판정됩니다</b> —
     * {@code RefreshTokenSessionPort.existsRefreshToken}과 {@code TokenBlacklistPort}가 그 일을 합니다.
     * 이 메서드만 보고 "검증되었다"고 읽으면 안 됩니다.
     */
    @Override
    public boolean validateRefreshToken(String refreshToken) {
        return StringUtils.hasText(refreshToken);
    }

    /**
     * @param accessToken 식별자를 추출할 액세스 토큰
     * @return 사용자 고유 ID (Long)
     * @throws BusinessException 토큰 형식이 잘못되었거나 서명이 유효하지 않은 경우
     * @responsibility 토큰의 {@code sub} 클레임에서 사용자 고유 식별자(ID)를 추출합니다.
     * @implNote <b>재발급(Reissue) 전략</b>: 만료된 액세스 토큰을 들고 재발급을 요청하는 경우를 위해,
     * {@link ExpiredJwtException}이 발생하더라도 예외 객체 내의 Claims에서 ID를 안전하게 추출하여 반환합니다.
     */
    @Override
    public Long getIdFromToken(String accessToken) {
        try {
            return resolvePayload(accessToken).userId();
        } catch (NumberFormatException e) {
            throw new AuthException(AuthErrorCode.JWT_TOKEN_IS_INVALID, "ID 형식이 올바르지 않습니다.");
        }
    }

    /**
     * @param accessToken 액세스 토큰
     * @return 사용자 권한(Role)
     */
    @Override
    public Role getRoleFromToken(String accessToken) {
        try {
            return resolvePayload(accessToken).role();
        } catch (IllegalArgumentException e) {
            throw new AuthException(AuthErrorCode.JWT_TOKEN_IS_INVALID, "유효하지 않은 Role 값입니다.");
        }
    }

    /**
     * @param accessToken 액세스 토큰
     * @return 토큰의 만료 시각
     * @responsibility 토큰으로부터 만료 시점({@link Instant})을 추출합니다.
     * @implNote 토큰이 이미 만료된 경우에도 Redis 블랙리스트 등록 등을 위해 만료 시점을 정확히 반환합니다.
     */
    @Override
    public Instant getExpirationFromToken(String accessToken) {
        try {
            // 새로 빌드하지 않고 주입받은 parser를 그대로 사용합니다.
            return parser.parseClaimsJws(accessToken).getBody().getExpiration().toInstant();
        } catch (ExpiredJwtException e) {
            // 이미 만료된 경우에도 Redis 블랙리스트 TTL 설정을 위해 만료 시점을 추출합니다.
            return e.getClaims().getExpiration().toInstant();
        } catch (JwtException e) {
            throw new AuthException(AuthErrorCode.JWT_TOKEN_IS_INVALID, e.getMessage());
        }
    }

    @Override
    public long getRemainingTimeFromAccessToken(String accessToken) {
        try {
            Instant expiration = getExpirationFromToken(accessToken);
            long now = System.currentTimeMillis();
            return expiration.toEpochMilli() - now;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * @responsibility <b>핵심 로직</b>: JWT의 Subject(암호문)를 추출하고 복호화하여 Map으로 변환합니다.
     * @implNote {@link ExpiredJwtException}이 발생해도 Claims를 꺼내 복호화를 시도합니다(Reissue 지원).
     */
    private Map<String, Object> getDecryptedPayload(String token) {
        String encryptedSubject;

        // 1. JWT 파싱 및 Subject(암호화된 문자열) 추출
        try {
            if (!StringUtils.hasText(token)) {
                throw new AuthException(AuthErrorCode.JWT_TOKEN_IS_EMPTY);
            }
            // 정상 토큰
            encryptedSubject = parser.parseClaimsJws(token).getBody().getSubject();
        } catch (ExpiredJwtException e) {
            // 만료된 토큰 -> 예외 객체에서 Claims 추출
            log.debug("만료된 토큰의 페이로드 복호화 시도");
            encryptedSubject = e.getClaims().getSubject();
        } catch (JwtException e) {
            throw new AuthException(AuthErrorCode.JWT_TOKEN_IS_INVALID, "토큰 서명 검증 실패");
        }

        if (!StringUtils.hasText(encryptedSubject)) {
            throw new AuthException(AuthErrorCode.JWT_TOKEN_IS_INVALID, "토큰에 식별자 정보가 없습니다.");
        }

        // 2. AES-GCM 복호화 및 JSON 파싱
        try {
            // "v1:IV:Cipher" -> JSON String
            String jsonPayload = encryptPort.decrypt(encryptedSubject);
            // JSON String -> Map<String, Object>
            return objectMapper.readValue(jsonPayload, typeReference);
        } catch (IOException e) {
            log.error("Token Decryption Failed: Failed to parse JSON payload", e);
            throw new AuthException(AuthErrorCode.JWT_TOKEN_IS_INVALID, "토큰 페이로드 파싱에 실패했습니다.");
        } catch (Exception e) {
            log.error("Token Decryption Failed", e);
            throw new AuthException(AuthErrorCode.JWT_TOKEN_IS_INVALID, "토큰 복호화에 실패했습니다.");
        }
    }

    private long extractId(Map<String, Object> claimsMap) {
        try {
            // objectMapper가 숫자를 Integer로 변환할 수 있으므로 String.valueOf()를 사용하는 것이 안전합니다.
            return Long.parseLong(String.valueOf(claimsMap.get(idKey)));
        } catch (NumberFormatException e) {
            throw new AuthException(AuthErrorCode.JWT_TOKEN_IS_INVALID, "ID 형식이 올바르지 않습니다.");
        }
    }

    private Role extractRole(Map<String, Object> claimsMap) {
        try {
            String roleStr = String.valueOf(claimsMap.get(roleKey));
            return Role.valueOf(roleStr);
        } catch (IllegalArgumentException e) {
            throw new AuthException(AuthErrorCode.JWT_TOKEN_IS_INVALID, "유효하지 않은 Role 값입니다.");
        }
    }
}