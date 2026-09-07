package com.serverbe.adapter.out.persistence.token;

import com.serverbe.infrastructure.config.properties.JwtProperties;
import com.serverbe.infrastructure.config.properties.RedisProperties;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * @responsibility 세션 어댑터가 조립하는 <b>Redis 키 모양</b>과 <b>Lua 스크립트에 넘기는 인자</b>를 고정합니다.
 * @implSpec 키 모양이 바뀌면 배포되는 순간 살아 있던 세션이 전부 무효가 됩니다. 컴파일로는 잡히지
 * 않고 통합 테스트도 없으므로 이 테스트가 유일한 방어선입니다. {@code RestApiResponseJsonContractTest}가
 * JSON 계약을 고정하는 것과 같은 성격입니다.
 * @implNote {@link TokenRedisKeys}를 <b>목이 아니라 실제 인스턴스로</b> 씁니다. 목으로 두면 고정하려던
 * 키 모양 자체가 사라집니다.
 * @implNote 목으로 검증되는 것은 <b>"스크립트에 이런 인자를 넘겼다"까지</b>입니다. 스크립트가 실제로
 * 원자적으로 도는지는 여기서 알 수 없습니다 — 그 경계는
 * {@code docs/refactor/11-test-gaps-persistence-adapters.md}에 적혀 있습니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("리프레시 토큰 세션 어댑터")
class RefreshTokenSessionAdapterTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private RedisScript<Boolean> saveTokenScript;
    @Mock
    private RedisScript<Boolean> rotateTokenScript;
    @Mock
    private RedisScript<Boolean> globalLogoutScript;
    @Mock
    private RedisScript<Boolean> deleteTokenScript;

    private static final Long USER_ID = 100L;
    private static final String DEVICE_ID = "device-A";
    private static final String TOKEN = "refresh.token.value";

    private static final String TOKEN_KEY = "user:100:rt:device-A";
    private static final String TOKEN_KEY_PREFIX = "user:100:rt:";
    private static final String SESSION_KEY = "user:session:100";

    private static final Duration REFRESH_TTL = Duration.ofDays(14);
    private static final int MAX_TOKEN = 3;

    private static final RedisProperties REDIS_PROPERTIES = new RedisProperties(
            "localhost", 6379,
            new RedisProperties.Auth("user", "rt", MAX_TOKEN),
            new RedisProperties.Session("session"),
            new RedisProperties.Blacklist("BL:AT", "BL:RT"),
            new RedisProperties.Shedlock("shedlock")
    );

    private RefreshTokenSessionAdapter adapter() {
        JwtProperties jwtProperties = new JwtProperties(
                "secret", null,
                new JwtProperties.RefreshToken("refreshToken", REFRESH_TTL, 32),
                "role", "id", 0
        );
        return new RefreshTokenSessionAdapter(
                redisTemplate, new TokenRedisKeys(REDIS_PROPERTIES), REDIS_PROPERTIES, jwtProperties,
                saveTokenScript, rotateTokenScript, globalLogoutScript, deleteTokenScript);
    }

    private static String refreshBlacklistKey(String token) {
        return "BL:RT:" + DigestUtils.sha256Hex(token);
    }

    /**
     * @implNote Mockito 5부터 배열 타입 캡터는 가변 인자 <b>전체</b>를 한 배열로 잡습니다.
     * 스크립트까지 함께 캡처하는 이유는 어댑터가 스크립트 넷을 들고 있어, 키와 인자가 맞아도
     * <b>다른 스크립트</b>에 넘어가면 전혀 다른 동작이 되기 때문입니다.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ScriptCall captureScriptCall() {
        ArgumentCaptor<RedisScript<Boolean>> script = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);

        verify(redisTemplate).execute(script.capture(), keys.capture(), args.capture());

        return new ScriptCall(script.getValue(), keys.getValue(), args.getValue());
    }

    private record ScriptCall(RedisScript<Boolean> script, List<String> keys, Object[] args) {
    }

    @Test
    @DisplayName("저장은 세션 인덱스와 토큰 키를 넘기고 인자 끝에 토큰 키 접두사를 붙인다")
    void 저장은_세션_인덱스와_토큰_키를_넘긴다() {
        adapter().saveRefreshToken(USER_ID, DEVICE_ID, TOKEN, Duration.ofMinutes(30));

        ScriptCall call = captureScriptCall();

        assertThat(call.script()).isSameAs(saveTokenScript);
        assertThat(call.keys()).containsExactly(SESSION_KEY, TOKEN_KEY);
        assertThat(call.args()).startsWith(
                DEVICE_ID,
                TOKEN,
                String.valueOf(Duration.ofMinutes(30).toMillis()));
        assertThat(call.args()[4]).isEqualTo(String.valueOf(REFRESH_TTL.toMillis()));
        assertThat(call.args()[5]).isEqualTo(String.valueOf(MAX_TOKEN));
        assertThat(call.args()[6]).isEqualTo(TOKEN_KEY_PREFIX);
    }

    @Test
    @DisplayName("조회는 기기별 토큰 키를 읽고, 없으면 null이다")
    void 조회는_기기별_토큰_키를_읽는다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(TOKEN_KEY)).willReturn(TOKEN, (String) null);

        RefreshTokenSessionAdapter adapter = adapter();

        assertThat(adapter.getRefreshToken(USER_ID, DEVICE_ID)).isEqualTo(TOKEN);
        assertThat(adapter.getRefreshToken(USER_ID, DEVICE_ID)).isNull();
    }

    @Test
    @DisplayName("개별 로그아웃은 세션 인덱스와 토큰 키, deviceId를 넘긴다")
    void 개별_로그아웃은_세션_인덱스와_토큰_키를_넘긴다() {
        adapter().deleteRefreshToken(USER_ID, DEVICE_ID);

        ScriptCall call = captureScriptCall();

        assertThat(call.script()).isSameAs(deleteTokenScript);
        assertThat(call.keys()).containsExactly(SESSION_KEY, TOKEN_KEY);
        assertThat(call.args()).containsExactly(DEVICE_ID);
    }

    @Test
    @DisplayName("전역 로그아웃은 세션 인덱스만 넘기고 나머지 키는 스크립트가 접두사로 재조립한다")
    void 전역_로그아웃은_세션_인덱스와_접두사를_넘긴다() {
        adapter().deleteAllRefreshTokens(USER_ID);

        ScriptCall call = captureScriptCall();

        assertThat(call.script()).isSameAs(globalLogoutScript);
        assertThat(call.keys()).containsExactly(SESSION_KEY);
        assertThat(call.args()).containsExactly(TOKEN_KEY_PREFIX);
    }

    @Test
    @DisplayName("기기 목록은 세션 인덱스를 오래된 순으로 읽는다")
    void 기기_목록은_세션_인덱스를_오래된_순으로_읽는다() {
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(zSetOperations.range(SESSION_KEY, 0, -1))
                .willReturn(new LinkedHashSet<>(List.of("device-A", "device-B")));

        assertThat(adapter().getAllDeviceIds(USER_ID)).containsExactly("device-A", "device-B");
    }

    @Test
    @DisplayName("기기 목록이 없으면 null이 아니라 빈 집합이다")
    void 기기_목록이_없으면_빈_집합이다() {
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(zSetOperations.range(SESSION_KEY, 0, -1)).willReturn(null);

        assertThat(adapter().getAllDeviceIds(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("가장 오래된 세션 축출은 인덱스의 첫 기기를 개별 로그아웃한다")
    void 가장_오래된_세션을_개별_로그아웃한다() {
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(zSetOperations.range(SESSION_KEY, 0, 0)).willReturn(Set.of("device-oldest"));

        adapter().removeOldestSession(USER_ID);

        ScriptCall call = captureScriptCall();

        assertThat(call.script()).isSameAs(deleteTokenScript);
        assertThat(call.keys()).containsExactly(SESSION_KEY, "user:100:rt:device-oldest");
        assertThat(call.args()).containsExactly("device-oldest");
    }

    @Test
    @DisplayName("축출할 세션이 없으면 아무것도 지우지 않는다")
    void 축출할_세션이_없으면_아무것도_지우지_않는다() {
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(zSetOperations.range(SESSION_KEY, 0, 0)).willReturn(Set.of());

        adapter().removeOldestSession(USER_ID);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("세션 개수는 인덱스의 원소 수이고, 인덱스가 없으면 0이다")
    void 세션_개수는_인덱스의_원소_수다() {
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(zSetOperations.zCard(SESSION_KEY)).willReturn(2L, (Long) null);

        RefreshTokenSessionAdapter adapter = adapter();

        assertThat(adapter.getSessionCount(USER_ID)).isEqualTo(2L);
        assertThat(adapter.getSessionCount(USER_ID)).isZero();
    }

    @Test
    @DisplayName("저장된 토큰과 문자열이 같을 때만 세션이 유효하다")
    void 저장된_토큰과_같을_때만_유효하다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(TOKEN_KEY)).willReturn(TOKEN, "other.token", null);

        RefreshTokenSessionAdapter adapter = adapter();

        assertThat(adapter.existsRefreshToken(USER_ID, DEVICE_ID, TOKEN)).isTrue();
        assertThat(adapter.existsRefreshToken(USER_ID, DEVICE_ID, TOKEN)).isFalse();
        assertThat(adapter.existsRefreshToken(USER_ID, DEVICE_ID, TOKEN)).isFalse();
    }

    @Test
    @DisplayName("남은 수명은 토큰 키의 PTTL이고, 만료되었거나 없으면 0이다")
    void 남은_수명은_토큰_키의_PTTL이다() {
        given(redisTemplate.getExpire(TOKEN_KEY, TimeUnit.MILLISECONDS))
                .willReturn(5_000L, -2L, null);

        RefreshTokenSessionAdapter adapter = adapter();

        assertThat(adapter.getSessionTtl(USER_ID, DEVICE_ID)).isEqualTo(5_000L);
        assertThat(adapter.getSessionTtl(USER_ID, DEVICE_ID)).isZero();
        assertThat(adapter.getSessionTtl(USER_ID, DEVICE_ID)).isZero();
    }

    /**
     * @implNote <b>세션 어댑터가 블랙리스트 키를 만드는 유일한 지점입니다.</b> 회전은 구 토큰 무효화와
     * 신 토큰 발급을 한 스크립트로 묶으므로 두 갈래를 함께 씁니다. 여기서 만드는 키가
     * {@code TokenBlacklistAdapterTest}가 고정하는 모양과 같아야 하고, 그래서 두 어댑터가
     * {@link TokenRedisKeys}를 공유합니다 — 어긋나면 회전한 구 토큰이 계속 유효해집니다.
     */
    @Test
    @DisplayName("회전은 세션 인덱스·새 토큰 키와 함께 구 토큰의 블랙리스트 키를 넘긴다")
    void 회전은_구_토큰의_블랙리스트_키를_함께_넘긴다() {
        adapter().rotateRefreshToken(USER_ID, DEVICE_ID, "old.token", "new.token", REFRESH_TTL);

        ScriptCall call = captureScriptCall();

        assertThat(call.script()).isSameAs(rotateTokenScript);
        assertThat(call.keys()).containsExactly(
                SESSION_KEY, TOKEN_KEY, refreshBlacklistKey("old.token"));
        assertThat(call.args()).startsWith(
                DEVICE_ID,
                "new.token",
                String.valueOf(REFRESH_TTL.toMillis()));
        assertThat(call.args()[6]).isEqualTo(TOKEN_KEY_PREFIX);
        assertThat(call.args()[7]).isEqualTo(String.valueOf(Duration.ofMinutes(5).toMillis()));
    }
}
