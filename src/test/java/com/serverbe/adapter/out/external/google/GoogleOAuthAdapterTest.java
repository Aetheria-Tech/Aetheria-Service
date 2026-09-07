package com.serverbe.adapter.out.external.google;

import com.serverbe.domain.exception.external.ExternalApiErrorCode;
import com.serverbe.domain.exception.external.ExternalApiException;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import com.serverbe.infrastructure.config.properties.GoogleProperties;
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
class GoogleOAuthAdapterTest {

    private MockWebServer mockWebServer;
    private GoogleOAuthAdapter googleOAuthAdapter;
    private GoogleOAuthFallbackHandler fallbackHandler;
    private CircuitBreaker googleCircuitBreaker;

    @Mock
    private GoogleProperties googleProperties;

    @Mock
    private GoogleProperties.Auth auth; // 🚀 중첩된 Auth 레코드 Mock 객체 추가

    @BeforeEach
    void setUp() throws IOException {
        // 1. MockWebServer 시작
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // 2. Mock Properties 설정 (MockWebServer의 URL을 구글 API 주소로 속임)
        String mockServerUrl = mockWebServer.url("/").toString();
        // 맨 끝의 '/' 제거
        mockServerUrl = mockServerUrl.substring(0, mockServerUrl.length() - 1);

        // 🚀 알려주신 GoogleProperties 구조에 맞게 Mocking
        given(googleProperties.auth()).willReturn(auth);
        given(auth.oauthApi()).willReturn(mockServerUrl); // 토큰 발급 API 베이스 URL
        given(auth.api()).willReturn(mockServerUrl);      // 유저 정보 API 베이스 URL
        given(auth.clientId()).willReturn("test-google-client-id");
        given(auth.clientSecret()).willReturn("test-google-secret");
        given(auth.redirectUri()).willReturn("http://localhost:8080/login/oauth2/code/google");

        // 3. Fallback Handler 인스턴스화
        fallbackHandler = new GoogleOAuthFallbackHandler();

        // 4. 테스트용 서킷 브레이커 설정 (2번만 호출되어도 상태 판단, 50% 실패 시 OPEN)
        CircuitBreakerConfig testConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .build();

        // 🚀 testConfig가 무조건 기본값으로 적용되도록 설정
        CircuitBreakerRegistry testRegistry = CircuitBreakerRegistry.of(testConfig);

        // 참고: GoogleOAuthAdapter는 "googleTokenApi"(토큰 발급)와 "googleUserInfoApi"(유저 정보 조회)
        // 서킷 브레이커를 각각 따로 등록합니다. circuitBreaker_Opens_And_Triggers_Fallback 테스트는
        // 토큰 발급 단계(getGoogleTokenResponse)에서 500 에러를 일으키므로 "googleTokenApi"를 관찰해야 합니다.
        // (이전에는 존재하지 않는 이름("googleApi")을 관찰하고 있어 실패가 전혀 기록되지 않았습니다.)
        this.googleCircuitBreaker = testRegistry.circuitBreaker("googleTokenApi");

        // 5. Adapter 초기화
        googleOAuthAdapter = new GoogleOAuthAdapter(
                fallbackHandler,
                googleProperties,
                WebClient.builder(),
                testRegistry
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("정상적인 인가 코드가 주어지면, 구글 서버에서 토큰과 유저 정보를 성공적으로 받아온다.")
    void getUserInfo_Success() {
        // given: 구글 토큰 API 응답 모킹
        String tokenResponseJson = """
                {
                  "access_token": "google-access-token",
                  "expires_in": 3599,
                  "refresh_token": "google-refresh-token",
                  "scope": "https://www.googleapis.com/auth/userinfo.email",
                  "token_type": "Bearer"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(tokenResponseJson));

        // given: 구글 유저 정보 API 응답 모킹
        String userInfoResponseJson = """
            {
              "sub": "104234567890",
              "email": "test@gmail.com",
              "verified_email": true,
              "name": "구글 테스터"
            }
            """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(userInfoResponseJson));

        // when
        var resultMono = googleOAuthAdapter.getUserInfo("valid-auth-code", OAuthProvider.GOOGLE);

        // then
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertThat(result.oauthId()).isEqualTo("104234567890");
                    assertThat(result.provider()).isEqualTo(OAuthProvider.GOOGLE);
                    assertThat(result.email()).isEqualTo("test@gmail.com");
                    assertThat(result.nickname()).isEqualTo("구글 테스터");
                    assertThat(result.oauthRefreshToken()).isEqualTo("google-refresh-token");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("구글 API 서버 오류(500) 발생 시 Fallback 핸들러가 동작하여 전용 예외를 반환한다.")
    void getUserInfo_Fallback_On_ServerError() {
        // given
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        // when
        var resultMono = googleOAuthAdapter.getUserInfo("auth-code", OAuthProvider.GOOGLE);

        // then
        StepVerifier.create(resultMono)
                .expectErrorMatches(throwable ->
                        throwable instanceof ExternalApiException &&
                                ((ExternalApiException) throwable).getErrorCode() == ExternalApiErrorCode.FAILED_SOCIAL_API &&
                                throwable.getMessage().contains("응답이 지연되고 있습니다"))
                .verify();
    }

    @Test
    @DisplayName("단기간에 구글 API 오류가 반복되어 서킷 브레이커가 OPEN 되면, 실제 호출 없이 즉시 Fallback 처리된다.")
    void circuitBreaker_Opens_And_Triggers_Fallback() throws InterruptedException {
        // given: 2번의 500 에러 응답
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Error 1"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Error 2"));

        // 첫 번째 실패 (1/2)
        StepVerifier.create(googleOAuthAdapter.getUserInfo("code1", OAuthProvider.GOOGLE))
                .expectError(ExternalApiException.class)
                .verify();

        // 두 번째 실패 (2/2)
        StepVerifier.create(googleOAuthAdapter.getUserInfo("code2", OAuthProvider.GOOGLE))
                .expectError(ExternalApiException.class)
                .verify();

        // 🚀 서킷 브레이커 상태가 OPEN으로 변경될 때까지 대기
        int retryCount = 0;
        while (!googleCircuitBreaker.getState().equals(CircuitBreaker.State.OPEN) && retryCount < 10) {
            Thread.sleep(100);
            retryCount++;
        }

        // 상태 검증
        assertThat(googleCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // when: 세 번째 호출 시도
        var thirdCallMono = googleOAuthAdapter.getUserInfo("code3", OAuthProvider.GOOGLE);

        // then: HTTP 요청 없이 즉시 차단(Fallback)되는지 확인
        StepVerifier.create(thirdCallMono)
                .expectErrorMatches(throwable ->
                        throwable instanceof ExternalApiException &&
                                ((ExternalApiException) throwable).getErrorCode() == ExternalApiErrorCode.FAILED_SOCIAL_API &&
                                throwable.getMessage().contains("잠시 후 다시 시도해주세요"))
                .verify();

        // 검증: MockWebServer에 도달한 요청은 2번뿐이어야 함
        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }
}