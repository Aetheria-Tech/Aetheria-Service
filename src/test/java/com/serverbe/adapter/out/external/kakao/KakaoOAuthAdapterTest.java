package com.serverbe.adapter.out.external.kakao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.domain.exception.external.ExternalApiErrorCode;
import com.serverbe.domain.exception.external.ExternalApiException;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import com.serverbe.infrastructure.config.properties.KakaoProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class KakaoOAuthAdapterTest {

    private MockWebServer mockWebServer;
    private KakaoOAuthAdapter kakaoOAuthAdapter;
    private KakaoOAuthFallbackHandler fallbackHandler;
    private CircuitBreaker kakaoCircuitBreaker;

    @Mock
    private KakaoProperties kakaoProperties;
    @Mock
    private KakaoProperties.Auth auth;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String mockServerUrl = mockWebServer.url("/").toString();
        mockServerUrl = mockServerUrl.substring(0, mockServerUrl.length() - 1);

        given(kakaoProperties.auth()).willReturn(auth);
        given(auth.kauth()).willReturn(mockServerUrl);
        given(auth.kapi()).willReturn(mockServerUrl);
        given(kakaoProperties.clientId()).willReturn("test-client-id");
        given(auth.redirectUri()).willReturn("http://localhost:8080/login/oauth2/code/kakao");

        fallbackHandler = new KakaoOAuthFallbackHandler();

        // 🚀 2번만 호출되어도 상태를 평가하도록 설정
        CircuitBreakerConfig testConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .build();

        // 🚀 [핵심 수정] Map 방식 대신, 이 레지스트리에서 생성되는 모든 서킷 브레이커가
        // 무조건 testConfig를 '기본값'으로 사용하도록 강제합니다.
        CircuitBreakerRegistry testRegistry = CircuitBreakerRegistry.of(testConfig);

        // 필드에서 상태를 검증하기 위해, 어댑터가 사용할 서킷 브레이커를 여기서 미리 꺼내어 동기화합니다.
        // 참고: KakaoOAuthAdapter는 "kakaoTokenApi"(토큰 발급)와 "kakaoUserInfoApi"(유저 정보 조회)
        // 서킷 브레이커를 각각 따로 등록합니다. circuitBreaker_Opens_And_Triggers_Fallback 테스트는
        // 토큰 발급 단계에서 500 에러를 일으키므로 "kakaoTokenApi"를 관찰해야 합니다.
        this.kakaoCircuitBreaker = testRegistry.circuitBreaker("kakaoTokenApi");

        kakaoOAuthAdapter = new KakaoOAuthAdapter(
                fallbackHandler,
                kakaoProperties,
                WebClient.builder(),
                testRegistry
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("정상적인 인가 코드가 주어지면, 카카오 서버에서 토큰과 유저 정보를 성공적으로 받아온다.")
    void getUserInfo_Success() throws JsonProcessingException {
        // given: 토큰 API 응답 모킹
        String tokenResponseJson = """
                {
                  "token_type": "bearer",
                  "access_token": "mock-access-token",
                  "refresh_token": "mock-refresh-token",
                  "expires_in": 43199
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(tokenResponseJson));

        // given: 유저 정보 API 응답 모킹
        String userInfoResponseJson = """
                {
                  "id": 123456789,
                  "kakao_account": {
                    "email": "test@kakao.com",
                    "profile": {
                      "nickname": "테스터"
                    }
                  }
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(userInfoResponseJson));

        // when
        var resultMono = kakaoOAuthAdapter.getUserInfo("valid-auth-code", OAuthProvider.KAKAO);

        // then: StepVerifier로 비동기 스트림 검증
        // then: StepVerifier로 비동기 스트림 검증
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertThat(result.oauthId()).isEqualTo("123456789");
                    assertThat(result.provider()).isEqualTo(OAuthProvider.KAKAO);
                    assertThat(result.email()).isEqualTo("test@kakao.com");
                    assertThat(result.nickname()).isEqualTo("테스터");
                    assertThat(result.oauthRefreshToken()).isEqualTo("mock-refresh-token");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("외부 API 서버 오류(500) 발생 시 Fallback 핸들러가 동작하여 전용 예외를 반환한다.")
    void getUserInfo_Fallback_On_ServerError() {
        // given: 카카오 토큰 서버가 500 에러를 반환하는 상황 모킹
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        // when
        var resultMono = kakaoOAuthAdapter.getUserInfo("auth-code", OAuthProvider.KAKAO);

        // then: Fallback 로직이 적용되었는지 확인 (FAILED_SOCIAL_API 예외 발생)
        StepVerifier.create(resultMono)
                .expectErrorMatches(throwable ->
                        throwable instanceof ExternalApiException &&
                                ((ExternalApiException) throwable).getErrorCode() == ExternalApiErrorCode.FAILED_SOCIAL_API &&
                                throwable.getMessage().contains("응답이 지연되고 있습니다"))
                .verify();
    }

    @Test
    @DisplayName("단기간에 오류가 반복되어 서킷 브레이커가 OPEN 되면, 실제 API 호출 없이 즉시 Fallback 처리된다.")
    void circuitBreaker_Opens_And_Triggers_Fallback() throws InterruptedException {
        // 1. Given: 2번의 실패 응답 설정
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Error 1"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Error 2"));

        // 2. 첫 번째 실패 (1/2)
        StepVerifier.create(kakaoOAuthAdapter.getUserInfo("code1", OAuthProvider.KAKAO))
                .expectError(ExternalApiException.class)
                .verify();

        // 3. 두 번째 실패 (2/2)
        StepVerifier.create(kakaoOAuthAdapter.getUserInfo("code2", OAuthProvider.KAKAO))
                .expectError(ExternalApiException.class)
                .verify();

        // 🚀 [중요] 서킷 브레이커 상태가 OPEN으로 변할 때까지 대기 (비동기 처리 대응)
        // 혹은 직접 서킷 객체를 꺼내와서 상태를 강제로 확인할 수도 있습니다.
        int retryCount = 0;
        while (!kakaoCircuitBreaker.getState().equals(CircuitBreaker.State.OPEN) && retryCount < 10) {
            Thread.sleep(100); // 0.1초씩 최대 1초 대기
            retryCount++;
        }

        // 서킷 상태가 OPEN인지 단언 (테스트 디버깅용)
        assertThat(kakaoCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 4. When: 세 번째 호출 시도
        var thirdCallMono = kakaoOAuthAdapter.getUserInfo("code3", OAuthProvider.KAKAO);

        // 5. Then: 실제 호출 없이 서킷 브레이커에 의해 차단되고 Fallback 실행되는지 확인
        StepVerifier.create(thirdCallMono)
                .expectErrorMatches(throwable ->
                        throwable instanceof ExternalApiException &&
                                ((ExternalApiException) throwable).getErrorCode() == ExternalApiErrorCode.FAILED_SOCIAL_API &&
                                throwable.getMessage().contains("잠시 후 다시 시도해주세요"))
                .verify();

        // 6. Verify: MockWebServer에 도달한 요청은 여전히 2개여야 함 (세 번째는 차단됨)
        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }
}