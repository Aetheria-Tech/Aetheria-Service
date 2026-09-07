package com.serverbe.adapter.out.persistence.token;

import com.serverbe.infrastructure.config.properties.RedisProperties;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * @responsibility 블랙리스트 어댑터가 조립하는 <b>해시 키 모양</b>과 <b>장애 시 정책</b>을 고정합니다.
 * @implSpec 키 모양이 바뀌면 그 순간 블랙리스트에 올려 둔 토큰이 전부 되살아납니다.
 * @implNote {@link TokenRedisKeys}를 <b>목이 아니라 실제 인스턴스로</b> 씁니다. 목으로 두면 고정하려던
 * 키 모양 자체가 사라집니다. 여기서 고정하는 RT 블랙리스트 키는
 * {@code RefreshTokenSessionAdapterTest}의 회전 테스트가 고정하는 것과 같은 모양이어야 합니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("토큰 블랙리스트 어댑터")
class TokenBlacklistAdapterTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private static final RedisProperties REDIS_PROPERTIES = new RedisProperties(
            "localhost", 6379,
            new RedisProperties.Auth("user", "rt", 3),
            new RedisProperties.Session("session"),
            new RedisProperties.Blacklist("BL:AT", "BL:RT"),
            new RedisProperties.Shedlock("shedlock")
    );

    private TokenBlacklistAdapter adapter() {
        return new TokenBlacklistAdapter(redisTemplate, new TokenRedisKeys(REDIS_PROPERTIES));
    }

    private static String accessBlacklistKey(String token) {
        return "BL:AT:" + DigestUtils.sha256Hex(token);
    }

    private static String refreshBlacklistKey(String token) {
        return "BL:RT:" + DigestUtils.sha256Hex(token);
    }

    @Test
    @DisplayName("액세스 토큰은 원문이 아니라 SHA-256 해시 키에 logout으로 저장된다")
    void 액세스_토큰은_해시_키에_저장된다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        adapter().blacklistAccessToken("access.token", Duration.ofMinutes(10));

        verify(valueOperations).set(
                accessBlacklistKey("access.token"), "logout", Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("리프레시 토큰도 해시 키에 저장되며 값이 used로 구분된다")
    void 리프레시_토큰도_해시_키에_저장된다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        adapter().blacklistRefreshToken("refresh.token", Duration.ofDays(1));

        verify(valueOperations).set(
                refreshBlacklistKey("refresh.token"), "used", Duration.ofDays(1));
    }

    @Test
    @DisplayName("액세스 토큰 조회는 해시 키의 존재 여부를 본다")
    void 액세스_토큰_조회는_해시_키를_본다() {
        given(redisTemplate.hasKey(accessBlacklistKey("access.token"))).willReturn(true);

        assertThat(adapter().isAccessTokenBlacklisted("access.token")).isTrue();
    }

    @Test
    @DisplayName("빈 토큰은 Redis를 건드리지 않는다")
    void 빈_토큰은_Redis를_건드리지_않는다() {
        assertThat(adapter().isAccessTokenBlacklisted("  ")).isFalse();

        verifyNoInteractions(redisTemplate);
    }

    /**
     * @implNote {@code AiTaskRedisAdapterTest}가 고정하는 SageMaker 비용 락은 <b>fail-closed</b>입니다.
     * 두 정책은 의도적으로 반대입니다 — 블랙리스트가 fail-closed면 Redis 장애가 곧 전면 인증 중단이고,
     * 비용 락이 fail-open이면 장애 시간 동안 추론 비용이 무제한으로 발생합니다.
     */
    @Test
    @DisplayName("Redis 연결이 끊겨도 인증을 막지 않는다 (Fail-Open)")
    void Redis_연결이_끊겨도_인증을_막지_않는다() {
        given(redisTemplate.hasKey(anyString()))
                .willThrow(new RedisConnectionFailureException("Redis Down"));

        assertThat(adapter().isAccessTokenBlacklisted("access.token")).isFalse();
    }

    /**
     * @implNote 이 테스트가 없는 동안 어댑터는 <b>JPA의</b>
     * {@code jakarta.persistence.QueryTimeoutException}을 잡고 있었습니다. Redis가 던지는 것은
     * 스프링 Data의 {@code org.springframework.dao.QueryTimeoutException}이라 감사 로그 분기는
     * 한 번도 실행되지 않았습니다. 반환값이 양쪽 다 {@code false}여서 <b>증상이 없었습니다.</b>
     */
    @Test
    @DisplayName("Redis 응답이 늦어도 인증을 막지 않는다 (Fail-Open)")
    void Redis_응답이_늦어도_인증을_막지_않는다() {
        given(redisTemplate.hasKey(anyString()))
                .willThrow(new QueryTimeoutException("Redis Timeout"));

        assertThat(adapter().isAccessTokenBlacklisted("access.token")).isFalse();
    }

    @Test
    @DisplayName("리프레시 토큰 조회는 해시 키의 존재 여부를 본다")
    void 리프레시_토큰_조회는_해시_키를_본다() {
        given(redisTemplate.hasKey(refreshBlacklistKey("refresh.token"))).willReturn(true);

        assertThat(adapter().isRefreshTokenBlacklisted("refresh.token")).isTrue();
    }
}
