package com.serverbe.adapter.out.external.kakao;

import com.serverbe.domain.exception.external.ExternalApiClientException;
import com.serverbe.domain.exception.external.ExternalApiErrorCode;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class KakaoGeocodeAdapterTest {

    private MockWebServer mockWebServer;
    private KakaoGeocodeAdapter kakaoGeocodeAdapter;
    private CircuitBreaker geocodeCircuitBreaker;

    @Mock
    private KakaoProperties kakaoProperties;

    @Mock
    private KakaoProperties.Geocoding geocoding;

    @Mock
    private KakaoGeocodeFallbackHandler fallbackHandler; // 🚀 실제 구현체가 없으므로 Mocking 처리

    @BeforeEach
    void setUp() throws IOException {
        // 1. MockWebServer 시작
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String mockServerUrl = mockWebServer.url("/").toString();
        mockServerUrl = mockServerUrl.substring(0, mockServerUrl.length() - 1);

        // 2. Mock Properties 설정
        given(kakaoProperties.geocoding()).willReturn(geocoding);
        given(geocoding.dapi()).willReturn(mockServerUrl);
        given(geocoding.geocodeApi()).willReturn("/v2/local/search/address.json");
        given(kakaoProperties.clientId()).willReturn("test-kakao-client-id");

        // 3. 서킷 브레이커 설정
        CircuitBreakerConfig testConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .build();
        CircuitBreakerRegistry testRegistry = CircuitBreakerRegistry.of(testConfig);
        this.geocodeCircuitBreaker = testRegistry.circuitBreaker("kakaoGeocodeApi");

        // 🚀 4. Fallback Handler 동작 정의 (lenient 적용)
        // 성공하는 테스트에서는 호출되지 않으므로 예외가 발생하지 않도록 lenient() 처리합니다.
        lenient().when(fallbackHandler.fallbackGeocode(anyString(), any(Throwable.class)))
                .thenAnswer(invocation -> Mono.error(invocation.getArgument(1, Throwable.class)));

        // 5. 어댑터 초기화
        kakaoGeocodeAdapter = new KakaoGeocodeAdapter(
                WebClient.builder(),
                kakaoProperties,
                fallbackHandler,
                testRegistry
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("정상적인 주소가 주어지면, 카카오 API에서 위경도 좌표를 성공적으로 파싱한다.")
    void geocode_Success() {
        // given: 카카오 지오코딩 API 응답 모킹 (🚨 주석 없이 순수 JSON만 작성!)
        String geocodeResponseJson = """
                {
                  "documents": [
                    {
                      "y": "37.402056",
                      "x": "127.108212",
                      "address_name": "경기 성남시 분당구 판교역로 166"
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(geocodeResponseJson));

        // when
        var resultMono = kakaoGeocodeAdapter.geocode("카카오 판교오피스");

        // then
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertThat(result.latitude()).isEqualTo(37.402056);
                    assertThat(result.longitude()).isEqualTo(127.108212);
                    assertThat(result.formattedAddress()).isEqualTo("경기 성남시 분당구 판교역로 166");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("카카오 API에서 빈 결과 리스트(documents: [])를 반환하면 INVALID_ADDRESS 예외가 발생한다.")
    void geocode_EmptyDocuments() {
        // given
        String emptyResponseJson = """
                {
                  "documents": []
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(emptyResponseJson));

        // when
        var resultMono = kakaoGeocodeAdapter.geocode("존재하지않는이상한주소");

        // then
        StepVerifier.create(resultMono)
                .expectErrorMatches(throwable ->
                        throwable instanceof ExternalApiClientException &&
                                ((ExternalApiClientException) throwable).getErrorCode() == ExternalApiErrorCode.INVALID_ADDRESS &&
                                throwable.getMessage().contains("검색 결과가 없습니다"))
                .verify();
    }

    @Test
    @DisplayName("카카오 API가 4xx 에러를 반환하면 INVALID_ADDRESS 예외가 발생한다.")
    void geocode_4xxError() {
        // given: 400 Bad Request 모킹
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("Bad Request"));

        // when
        var resultMono = kakaoGeocodeAdapter.geocode("잘못된요청파라미터주소");

        // then
        StepVerifier.create(resultMono)
                .expectErrorMatches(throwable ->
                        throwable instanceof ExternalApiClientException &&
                                ((ExternalApiClientException) throwable).getErrorCode() == ExternalApiErrorCode.INVALID_ADDRESS &&
                                throwable.getMessage().contains("잘못된 주소로 요청"))
                .verify();
    }

    @Test
    @DisplayName("지속적인 500 에러 발생 시 서킷 브레이커가 OPEN 되어 HTTP 요청 없이 차단된다.")
    void circuitBreaker_Opens_On_ServerError() throws InterruptedException {
        // given: 2번의 500 에러 응답
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Error 1"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Error 2"));

        // 1~2번째 호출하여 서킷 브레이커 실패 카운트 누적
        StepVerifier.create(kakaoGeocodeAdapter.geocode("주소1")).expectError().verify();
        StepVerifier.create(kakaoGeocodeAdapter.geocode("주소2")).expectError().verify();

        // 서킷 브레이커 상태가 OPEN 될 때까지 잠시 대기
        int retryCount = 0;
        while (!geocodeCircuitBreaker.getState().equals(CircuitBreaker.State.OPEN) && retryCount < 10) {
            Thread.sleep(100);
            retryCount++;
        }
        assertThat(geocodeCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // when & then: 3번째 호출은 MockWebServer 에 도달하지 않고 CallNotPermittedException 발생 후 Fallback 으로 전달됨
        StepVerifier.create(kakaoGeocodeAdapter.geocode("주소3"))
                .expectErrorMatches(throwable ->
                        throwable instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException)
                .verify();

        // 3번째 요청은 서킷 브레이커가 막았으므로, 서버로 날아간 요청은 2번이어야 함
        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }
}