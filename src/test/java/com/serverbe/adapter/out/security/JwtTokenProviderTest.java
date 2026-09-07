package com.serverbe.adapter.out.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.application.port.out.crypto.EncryptPort;
import com.serverbe.application.port.out.dto.oauth.AccessTokenResult;
import com.serverbe.application.port.out.dto.oauth.RefreshTokenResult;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.domain.model.user.vo.Role;
import com.serverbe.infrastructure.config.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * @responsibility 발급되는 토큰의 <b>형식과 만료 시각</b>을 고정합니다.
 * @implSpec 가장 중요한 단언은 <b>액세스 토큰의 {@code subject}가 평문이 아니라는 것</b>입니다.
 * JWT의 페이로드는 서명될 뿐 암호화되지 않으므로, 누구나 Base64 디코드로 읽을 수 있습니다.
 * 여기서 암호화가 빠지면 <b>사용자 ID와 권한이 토큰에 그대로 노출됩니다</b> — 토큰은 여전히 잘
 * 동작하므로 아무 증상이 없습니다.
 * @implNote {@code EncryptPort}만 목으로 두어 <b>항등 함수</b>로 만듭니다. 암호화 자체의 정확성은
 * {@code AesGcmEncryptorTest}가 이미 보고 있고, 여기서 봐야 하는 것은 "암호화를 거쳤는가"입니다.
 * 키와 파서는 실제 {@link JwtKeyManager}를 씁니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JWT 토큰 발급기")
class JwtTokenProviderTest {

    @Mock
    private EncryptPort encryptPort;

    private JwtTokenProvider provider;
    private JwtKeyManager keyManager;

    private static final Long USER_ID = 42L;
    private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
    private static final Duration REFRESH_TTL = Duration.ofDays(14);
    private static final int REFRESH_BYTES = 32;

    static JwtProperties jwtProperties(Duration accessTtl, long clockSkewSeconds) {
        String secret = Base64.getEncoder().encodeToString(
                "test-secret-key-that-is-long-enough-for-hs512-algorithm-0123456789".getBytes());
        return new JwtProperties(
                secret,
                new JwtProperties.AccessToken("Bearer", "Authorization", accessTtl),
                new JwtProperties.RefreshToken("refreshToken", REFRESH_TTL, REFRESH_BYTES),
                "role", "id", clockSkewSeconds);
    }

    @BeforeEach
    void setUp() {
        JwtProperties properties = jwtProperties(ACCESS_TTL, 0);
        keyManager = new JwtKeyManager(properties);
        provider = new JwtTokenProvider(
                new SecureRandom(), encryptPort, new ObjectMapper(), properties, keyManager);
    }

    @Test
    @DisplayName("액세스 토큰의 subject는 평문이 아니라 암호문이다")
    void 액세스_토큰의_subject는_암호문이다() {
        given(encryptPort.encrypt(anyString())).willReturn("v1:IV:CIPHER");

        AccessTokenResult result = provider.generateAccessToken(USER_ID, Role.USER);

        Jws<Claims> parsed = keyManager.getParser().parseClaimsJws(result.accessToken());

        assertThat(parsed.getBody().getSubject()).isEqualTo("v1:IV:CIPHER");
        assertThat(result.accessToken()).doesNotContain("42").doesNotContain("USER");
    }

    /**
     * @implNote 암호화에 넘기기 전의 JSON이 설정된 키 이름을 쓰는지 봅니다. {@code idKey}·
     * {@code roleKey}가 바뀌면 <b>발급은 되는데 해석이 안 되는</b> 토큰이 나옵니다.
     */
    @Test
    @DisplayName("암호화 전 페이로드는 설정된 id·role 키를 쓴다")
    void 암호화_전_페이로드는_설정된_키를_쓴다() throws Exception {
        given(encryptPort.encrypt(anyString())).willReturn("cipher");

        provider.generateAccessToken(USER_ID, Role.ADMIN);

        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(encryptPort).encrypt(json.capture());

        Map<String, Object> payload = new ObjectMapper().readValue(json.getValue(), Map.class);
        assertThat(payload).containsOnlyKeys("id", "role")
                .containsEntry("id", 42)
                .containsEntry("role", "ADMIN");
    }

    /**
     * @implNote JWT의 {@code exp}는 <b>초 단위</b>라 토큰에 실리면서 밀리초가 잘립니다. 그래서
     * {@code expireIn}(자르기 전 밀리초)과 토큰에서 되읽은 값은 최대 1초 어긋날 수 있습니다 —
     * 같은 초를 가리키는지로 봅니다. 이 차이를 모르고 정확히 같기를 기대하면 <b>테스트가 실행
     * 시각에 따라 깜빡입니다.</b>
     */
    @Test
    @DisplayName("만료 시각은 설정된 유효 기간만큼 뒤이고 expireIn과 같은 초를 가리킨다")
    void 만료_시각은_설정된_유효_기간만큼_뒤다() {
        given(encryptPort.encrypt(anyString())).willReturn("cipher");

        long before = System.currentTimeMillis();
        AccessTokenResult result = provider.generateAccessToken(USER_ID, Role.USER);
        long after = System.currentTimeMillis();

        long expiration = keyManager.getParser()
                .parseClaimsJws(result.accessToken()).getBody().getExpiration().getTime();

        assertThat(expiration / 1000).isEqualTo(result.expireIn() / 1000);
        assertThat(result.expireIn())
                .isBetween(before + ACCESS_TTL.toMillis(), after + ACCESS_TTL.toMillis());
    }

    /**
     * @implNote 리프레시 토큰은 <b>정보를 담지 않는 난수</b>입니다. 쿠키와 Redis 키로 오가므로
     * URL-safe 알파벳이어야 하고, 패딩({@code =})이 붙으면 경로·쿼리에서 인코딩 사고가 납니다.
     */
    @Test
    @DisplayName("리프레시 토큰은 패딩 없는 URL-safe 난수이고 매번 다르다")
    void 리프레시_토큰은_패딩_없는_URL_safe_난수다() {
        RefreshTokenResult first = provider.generateRefreshToken(USER_ID, Role.USER);
        RefreshTokenResult second = provider.generateRefreshToken(USER_ID, Role.USER);

        assertThat(first.opaqueToken())
                .doesNotContain("=").doesNotContain("+").doesNotContain("/")
                .matches("[A-Za-z0-9_-]+");
        assertThat(first.opaqueToken()).isNotEqualTo(second.opaqueToken());
        assertThat(Base64.getUrlDecoder().decode(first.opaqueToken())).hasSize(REFRESH_BYTES);
        assertThat(first.name()).isEqualTo(String.valueOf(USER_ID));
    }

    @Test
    @DisplayName("암호화가 실패하면 발급 실패 예외로 번역된다")
    void 암호화가_실패하면_발급_실패로_번역된다() {
        given(encryptPort.encrypt(anyString())).willThrow(new IllegalStateException("키 없음"));

        assertThatThrownBy(() -> provider.generateAccessToken(USER_ID, Role.USER))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.JWT_GENERATION_FAILED);
    }
}
