package com.serverbe.adapter.out.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.application.port.out.crypto.EncryptPort;
import com.serverbe.application.port.out.security.dto.JwtPayloadDto;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.domain.model.user.vo.Role;
import com.serverbe.infrastructure.config.properties.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * @responsibility 토큰 해석기의 <b>거절 경로</b>를 고정합니다.
 * @implSpec 이 클래스가 하는 일은 "이 토큰을 믿어도 되는가"의 판단이고, <b>틀렸을 때의 실패
 * 방향이 인증 우회</b>입니다. 서명 검증이 느슨해지거나 만료 검사가 사라져도 정상 요청은 그대로
 * 통과하므로 <b>증상이 없습니다.</b> 그래서 성공 경로보다 거절 경로가 이 테스트의 본체입니다.
 * @implSpec <b>만료된 토큰에서도 페이로드를 꺼낼 수 있어야 합니다.</b> 재발급은 만료된 액세스
 * 토큰을 들고 오는 흐름이라, 그 예외 복구가 깨지면 재발급이 전부 실패합니다. 만료 검사와 페이로드
 * 추출이 서로 다른 정책이라는 것이 이 클래스의 핵심입니다.
 * @implNote clock skew를 <b>0</b>으로 두어야 만료 케이스가 결정적으로 동작합니다. 운영 설정은
 * 서버 간 시간 오차를 허용하지만, 그 허용치가 테스트에 들어오면 "만료됐는데 통과"가 됩니다.
 * @implNote {@code JwtAuthenticationFilterTest}는 이 클래스를 <b>목으로</b> 두고 필터가 조립하는
 * 인증 객체의 모양만 봅니다 — 판단 자체를 보는 것은 여기뿐입니다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JWT 토큰 해석기")
class JwtTokenResolverTest {

    @Mock
    private EncryptPort encryptPort;

    private JwtTokenResolver resolver;
    private SecretKey key;

    private static final Long USER_ID = 42L;
    private static final String PLAIN_PAYLOAD = "{\"id\":42,\"role\":\"USER\"}";
    private static final String CIPHER = "v1:IV:CIPHER";

    @BeforeEach
    void setUp() {
        JwtProperties properties = JwtTokenProviderTest.jwtProperties(java.time.Duration.ofMinutes(30), 0);
        JwtKeyManager keyManager = new JwtKeyManager(properties);
        this.key = keyManager.getKey();
        this.resolver = new JwtTokenResolver(keyManager, properties, encryptPort, new ObjectMapper());

        given(encryptPort.decrypt(CIPHER)).willReturn(PLAIN_PAYLOAD);
    }

    private String token(String subject, Instant expiresAt) {
        return Jwts.builder()
                .setSubject(subject)
                .setIssuedAt(new Date())
                .setExpiration(Date.from(expiresAt))
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();
    }

    private String validToken() {
        return token(CIPHER, Instant.now().plusSeconds(600));
    }

    private String expiredToken() {
        return token(CIPHER, Instant.now().minusSeconds(600));
    }

    /**
     * @implNote 다른 키로 서명한 토큰입니다. 구조는 완벽히 정상이고 <b>서명만</b> 다릅니다 —
     * 서명 검증을 건너뛰는 구현이면 그대로 통과합니다.
     */
    private String forgedToken() {
        SecretKey otherKey = Keys.hmacShaKeyFor(Base64.getEncoder()
                .encode("a-completely-different-signing-key-0123456789abcdef".getBytes()));
        return Jwts.builder()
                .setSubject(CIPHER)
                .setExpiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(otherKey, SignatureAlgorithm.HS512)
                .compact();
    }

    @Nested
    @DisplayName("검증 — 예외가 아니라 false로 나간다")
    class 검증 {

        @Test
        @DisplayName("정상 토큰만 통과한다")
        void 정상_토큰만_통과한다() {
            assertThat(resolver.validateAccessToken(validToken())).isTrue();
        }

        @Test
        @DisplayName("다른 키로 서명한 토큰은 거절한다")
        void 다른_키로_서명한_토큰은_거절한다() {
            assertThat(resolver.validateAccessToken(forgedToken())).isFalse();
        }

        @Test
        @DisplayName("만료된 토큰은 거절한다")
        void 만료된_토큰은_거절한다() {
            assertThat(resolver.validateAccessToken(expiredToken())).isFalse();
        }

        @Test
        @DisplayName("빈 값과 형식이 아닌 문자열도 예외 없이 거절한다")
        void 빈_값과_쓰레기_문자열도_거절한다() {
            assertThat(resolver.validateAccessToken(null)).isFalse();
            assertThat(resolver.validateAccessToken("   ")).isFalse();
            assertThat(resolver.validateAccessToken("not.a.jwt")).isFalse();
        }

        /**
         * @implNote <b>리프레시 토큰 검증은 빈 문자열만 봅니다.</b> 이 메서드의 javadoc은 오랫동안
         * "설정된 길이와 일치하는지 확인한다"고 적혀 있었지만 그런 검사는 코드에 없었고, 12번
         * 항목에서 주석을 사실에 맞췄습니다. 실제 대조는 저장소(Redis)에 저장된 값과의 비교로
         * 이뤄집니다 — {@code RefreshTokenSessionPort.existsRefreshToken}.
         */
        @Test
        @DisplayName("리프레시 토큰 검증은 값의 존재만 본다")
        void 리프레시_토큰_검증은_존재만_본다() {
            assertThat(resolver.validateRefreshToken("아무 문자열")).isTrue();
            assertThat(resolver.validateRefreshToken("x")).isTrue();
            assertThat(resolver.validateRefreshToken("  ")).isFalse();
            assertThat(resolver.validateRefreshToken(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("페이로드 해석")
    class 페이로드_해석 {

        @Test
        @DisplayName("정상 토큰에서 id와 role을 꺼낸다")
        void 정상_토큰에서_id와_role을_꺼낸다() {
            JwtPayloadDto payload = resolver.resolvePayload(validToken());

            assertThat(payload.userId()).isEqualTo(USER_ID);
            assertThat(payload.role()).isEqualTo(Role.USER);
        }

        /**
         * @implNote <b>재발급 전략의 핵심입니다.</b> 만료된 액세스 토큰을 들고 오는 것이 재발급의
         * 정상 흐름이므로, 만료를 이유로 페이로드 추출까지 막으면 재발급이 전부 실패합니다.
         * 만료 검사({@code validateAccessToken})와 페이로드 추출은 <b>다른 정책</b>입니다.
         */
        @Test
        @DisplayName("만료된 토큰에서도 페이로드를 꺼낼 수 있다")
        void 만료된_토큰에서도_페이로드를_꺼낸다() {
            assertThat(resolver.resolvePayload(expiredToken()).userId()).isEqualTo(USER_ID);
            assertThat(resolver.getIdFromToken(expiredToken())).isEqualTo(USER_ID);
            assertThat(resolver.getRoleFromToken(expiredToken())).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("다른 키로 서명한 토큰은 해석을 거부한다")
        void 다른_키로_서명한_토큰은_해석을_거부한다() {
            assertThatThrownBy(() -> resolver.resolvePayload(forgedToken()))
                    .isInstanceOf(AuthException.class)
                    .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.JWT_TOKEN_IS_INVALID);
        }

        @Test
        @DisplayName("빈 토큰은 비어 있다는 것을 구분해서 알린다")
        void 빈_토큰은_구분해서_알린다() {
            assertThatThrownBy(() -> resolver.resolvePayload("  "))
                    .isInstanceOf(AuthException.class)
                    .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.JWT_TOKEN_IS_EMPTY);
        }

        @Test
        @DisplayName("복호화가 실패하면 유효하지 않은 토큰으로 번역된다")
        void 복호화_실패는_유효하지_않은_토큰이_된다() {
            given(encryptPort.decrypt(anyString())).willThrow(new IllegalStateException("키 불일치"));

            assertThatThrownBy(() -> resolver.resolvePayload(validToken()))
                    .isInstanceOf(AuthException.class)
                    .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.JWT_TOKEN_IS_INVALID);
        }

        /**
         * @implNote <b>도달 불가 분기의 증거입니다.</b> {@code getIdFromToken}은
         * {@code NumberFormatException}을 잡도록 쓰여 있지만, 내부 {@code extractId}가 이미
         * {@code AuthException}으로 바꿔 던지므로 그 catch에는 아무것도 도달하지 않습니다.
         * {@code getRoleFromToken}의 {@code IllegalArgumentException} 분기도 같습니다.
         * 지우는 것은 12번 항목의 범위가 아니라 사실만 남깁니다.
         */
        @Test
        @DisplayName("id·role 형식 오류는 내부에서 이미 도메인 예외로 바뀌어 나온다")
        void 형식_오류는_내부에서_이미_번역된다() {
            given(encryptPort.decrypt(CIPHER)).willReturn("{\"id\":\"숫자가아님\",\"role\":\"USER\"}");
            assertThatThrownBy(() -> resolver.getIdFromToken(validToken()))
                    .isInstanceOf(AuthException.class)
                    .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.JWT_TOKEN_IS_INVALID);

            given(encryptPort.decrypt(CIPHER)).willReturn("{\"id\":42,\"role\":\"없는권한\"}");
            assertThatThrownBy(() -> resolver.getRoleFromToken(validToken()))
                    .isInstanceOf(AuthException.class)
                    .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.JWT_TOKEN_IS_INVALID);
        }
    }

    @Nested
    @DisplayName("만료 시각 — 블랙리스트 TTL이 여기에 의존한다")
    class 만료_시각 {

        /**
         * @implNote 만료된 토큰에서도 만료 시각이 나와야 합니다. 로그아웃 시 <b>남은 수명만큼만</b>
         * 블랙리스트에 올리는데, 여기서 예외가 나면 그 계산이 무너집니다.
         */
        @Test
        @DisplayName("만료된 토큰에서도 만료 시각을 돌려준다")
        void 만료된_토큰에서도_만료_시각을_돌려준다() {
            assertThat(resolver.getExpirationFromToken(expiredToken()))
                    .isBefore(Instant.now());
            assertThat(resolver.getExpirationFromToken(validToken()))
                    .isAfter(Instant.now());
        }

        @Test
        @DisplayName("해석할 수 없는 토큰은 유효하지 않은 토큰으로 번역된다")
        void 해석할_수_없는_토큰은_번역된다() {
            assertThatThrownBy(() -> resolver.getExpirationFromToken("not.a.jwt"))
                    .isInstanceOf(AuthException.class)
                    .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.JWT_TOKEN_IS_INVALID);
        }

        @Test
        @DisplayName("남은 시간은 만료된 토큰과 쓰레기 입력 모두에서 0 이하다")
        void 남은_시간은_만료와_쓰레기_입력에서_0_이하다() {
            assertThat(resolver.getRemainingTimeFromAccessToken(validToken())).isPositive();
            assertThat(resolver.getRemainingTimeFromAccessToken(expiredToken())).isNegative();
            assertThat(resolver.getRemainingTimeFromAccessToken("not.a.jwt")).isZero();
        }
    }
}
