package com.serverbe.adapter.out.external.kakao;

import com.serverbe.adapter.out.external.kakao.dto.KakaoTokenResponse;
import com.serverbe.adapter.out.external.kakao.dto.KakaoUserInfoResponse;
import com.serverbe.application.port.out.dto.oauth.OAuthUserInfoResult;
import com.serverbe.application.port.out.oauth.OAuthClientPort;
import com.serverbe.domain.exception.external.ExternalApiClientException;
import com.serverbe.domain.exception.external.ExternalApiErrorCode;
import com.serverbe.domain.exception.external.ExternalApiException;
import com.serverbe.domain.exception.server.ServerErrorCode;
import com.serverbe.domain.exception.server.ServerException;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import com.serverbe.infrastructure.config.properties.KakaoProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * @author Duskafka
 * @responsibility 카카오 OAuth 통신을 책임진다
 * @see OAuthClientPort
 */
@Slf4j
@Component
public class KakaoOAuthAdapter implements OAuthClientPort {

    private final KakaoOAuthFallbackHandler fallbackHandler;
    private final CircuitBreaker kakaoTokenCircuitBreaker; // kauth 용
    private final CircuitBreaker kakaoApiCircuitBreaker;   // kapi 용

    private final WebClient webClient;
    private final String kauthUrl;
    private final String kapiUrl;
    private final String clientId;
    private final String adminKey;
    private final String redirectUri;

    public KakaoOAuthAdapter(
            KakaoOAuthFallbackHandler kakaoOAuthFallbackHandler,
            KakaoProperties kakaoProperties,
            WebClient.Builder webClientBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        this.fallbackHandler = kakaoOAuthFallbackHandler;

        this.kauthUrl = kakaoProperties.auth().kauth();
        this.kapiUrl = kakaoProperties.auth().kapi();
        this.clientId = kakaoProperties.clientId();
        this.adminKey = kakaoProperties.adminKey();
        this.redirectUri = kakaoProperties.auth().redirectUri();

        this.webClient = webClientBuilder.clone().build();

        this.kakaoTokenCircuitBreaker = circuitBreakerRegistry.circuitBreaker("kakaoTokenApi");
        this.kakaoApiCircuitBreaker = circuitBreakerRegistry.circuitBreaker("kakaoUserInfoApi");
    }

    @Override
    public Mono<OAuthUserInfoResult> getUserInfo(String code, OAuthProvider provider) {
        if (provider != OAuthProvider.KAKAO) {
            return Mono.error(new ServerException(
                    ServerErrorCode.INTERNAL_SERVER_ERROR,
                    "카카오 어댑터는 카카오 로그인만 처리할 수 있습니다."
            ));
        }

        return getKakaoAccessToken(code)
                .flatMap(accessToken -> fetchUserInfo(
                                accessToken.accessToken(),
                                accessToken.refreshToken()
                        )
                )
                // 두 통신 중 하나라도 실패(타임아웃/서킷오픈)하면 여기서 일괄적으로 Fallback 처리됩니다.
                .onErrorResume(throwable -> fallbackHandler.fallbackGetUserInfo(code, provider, throwable));
    }

    private Mono<KakaoTokenResponse> getKakaoAccessToken(String code) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "authorization_code");
        formData.add("client_id", clientId);
        formData.add("redirect_uri", redirectUri);
        formData.add("code", code);

        return webClient.post()
                .uri(kauthUrl + "/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res -> res.bodyToMono(String.class)
                        .flatMap(body -> {
                            log.warn("Kakao Token API Client Error (4xx): status={}, body={}", res.statusCode(), body);
                            return Mono.error(new ExternalApiClientException(ExternalApiErrorCode.FAILED_SOCIAL_API, "잘못된 카카오 인증 요청입니다.: " + body));
                        }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> res.bodyToMono(String.class)
                        .flatMap(body -> {
                            log.error("Kakao Token API Server Error (5xx): status={}, body={}", res.statusCode(), body);
                            return Mono.error(new ExternalApiException(ExternalApiErrorCode.FAILED_SOCIAL_API, "Kakao 서버 에러: " + body));
                        }))
                .bodyToMono(KakaoTokenResponse.class)
                .timeout(Duration.ofSeconds(2))
                .transformDeferred(CircuitBreakerOperator.of(kakaoTokenCircuitBreaker));
    }

    private Mono<OAuthUserInfoResult> fetchUserInfo(String accessToken, String refreshToken) {
        return webClient.get()
                .uri(kapiUrl + "/v2/user/me")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res -> res.bodyToMono(String.class)
                        .flatMap(body -> {
                            log.warn("Kakao UserInfo API Client Error (4xx): status={}, body={}", res.statusCode(), body);
                            return Mono.error(new ExternalApiClientException(ExternalApiErrorCode.FAILED_SOCIAL_API, "잘못된 사용자 정보 요청입니다.: " + body));
                        }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> res.bodyToMono(String.class)
                        .flatMap(body -> {
                            log.error("Kakao UserInfo API Server Error (5xx): status={}, body={}", res.statusCode(), body);
                            return Mono.error(new ExternalApiException(ExternalApiErrorCode.FAILED_SOCIAL_API, "Kakao 서버 에러: " + body));
                        }))
                .bodyToMono(KakaoUserInfoResponse.class)
                .map(response -> new OAuthUserInfoResult(
                        String.valueOf(response.id()),
                        OAuthProvider.KAKAO,
                        response.kakaoAccount().email(),
                        response.kakaoAccount().profile().nickname(),
                        refreshToken
                ))
                .timeout(Duration.ofSeconds(2))
                .transformDeferred(CircuitBreakerOperator.of(kakaoApiCircuitBreaker));
    }

    @Override
    public Mono<Boolean> unlink(OAuthProvider provider, String oauthId, String oauthRefreshToken) {
        return webClient.post()
                .uri(kapiUrl + "/v1/user/unlink")
                .header("Authorization", "KakaoAK " + adminKey)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("target_id_type", "user_id")
                        .with("target_id", oauthId))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res -> res.bodyToMono(String.class)
                        .flatMap(body -> {
                            log.warn("Kakao Unlink API Client Error (4xx): status={}, body={}", res.statusCode(), body);
                            return Mono.error(new ExternalApiClientException(ExternalApiErrorCode.FAILED_SOCIAL_API, "잘못된 연동 해제 요청입니다.: " + body));
                        }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> res.bodyToMono(String.class)
                        .flatMap(body -> {
                            log.error("Kakao Unlink API Server Error (5xx): status={}, body={}", res.statusCode(), body);
                            return Mono.error(new ExternalApiException(ExternalApiErrorCode.FAILED_SOCIAL_API, "Kakao 서버 에러: " + body));
                        }))
                .toBodilessEntity()
                .map(response -> true)
                .defaultIfEmpty(false)
                .timeout(Duration.ofSeconds(2))
                .transformDeferred(CircuitBreakerOperator.of(kakaoApiCircuitBreaker))
                .onErrorResume(throwable -> fallbackHandler.fallbackUnlink(provider, oauthId, oauthRefreshToken, throwable));
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.KAKAO;
    }

    @Override
    public String getLoginUrl() {
        return UriComponentsBuilder.fromHttpUrl(kauthUrl + "/oauth/authorize")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .build()
                .toUriString();
    }
}