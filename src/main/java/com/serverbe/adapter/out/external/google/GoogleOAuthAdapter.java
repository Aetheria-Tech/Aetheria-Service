package com.serverbe.adapter.out.external.google;

import com.serverbe.adapter.out.external.google.dto.GoogleTokenResponse;
import com.serverbe.adapter.out.external.google.dto.GoogleUserInfoResponse;
import com.serverbe.application.port.out.dto.oauth.OAuthUserInfoResult;
import com.serverbe.application.port.out.oauth.OAuthClientPort;
import com.serverbe.domain.exception.external.ExternalApiClientException;
import com.serverbe.domain.exception.external.ExternalApiErrorCode;
import com.serverbe.domain.exception.external.ExternalApiException;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import com.serverbe.infrastructure.config.properties.GoogleProperties;
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

import java.net.URI;
import java.time.Duration;

/**
 * @author Duskafka
 * @responsibility 구글 OAuth 서버와 협력하여 사용자 인증 관리를 하는 책임.
 * @see OAuthClientPort
 */
@Slf4j
@Component
public class GoogleOAuthAdapter implements OAuthClientPort {

    private final GoogleOAuthFallbackHandler fallbackHandler;

    private final CircuitBreaker googleTokenCircuitBreaker;
    private final CircuitBreaker googleApiCircuitBreaker;

    private final String oauthUrl;
    private final String apiUrl;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    private final WebClient webClient;

    public GoogleOAuthAdapter(
            GoogleOAuthFallbackHandler fallbackHandler,
            GoogleProperties googleProperties,
            WebClient.Builder webClientBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        this.fallbackHandler = fallbackHandler;
        this.webClient = webClientBuilder.clone().build();

        this.googleTokenCircuitBreaker = circuitBreakerRegistry.circuitBreaker("googleTokenApi");
        this.googleApiCircuitBreaker = circuitBreakerRegistry.circuitBreaker("googleUserInfoApi");

        this.oauthUrl = googleProperties.auth().oauthApi();
        this.apiUrl = googleProperties.auth().api();
        this.clientId = googleProperties.auth().clientId();
        this.clientSecret = googleProperties.auth().clientSecret();
        this.redirectUri = googleProperties.auth().redirectUri();
    }

    @Override
    public Mono<OAuthUserInfoResult> getUserInfo(String code, OAuthProvider provider) {
        return getGoogleTokenResponse(code)
                .flatMap(response -> this.fetchUserInfo(response.accessToken())
                        .map(userInfo -> new OAuthUserInfoResult(
                                userInfo.sub(),
                                OAuthProvider.GOOGLE,
                                userInfo.email(),
                                userInfo.name(),
                                response.refreshToken() // 여기서 매번 받은 리프레시 토큰을 넘깁니다.
                        )))
                // 두 구간 중 어디서든 에러가 발생하면 Fallback 처리
                .onErrorResume(throwable -> fallbackHandler.fallbackGetUserInfo(code, provider, throwable));
    }

    private Mono<GoogleTokenResponse> getGoogleTokenResponse(String code) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("code", code);
        formData.add("client_id", clientId);
        formData.add("client_secret", clientSecret);
        formData.add("redirect_uri", redirectUri);
        formData.add("grant_type", "authorization_code");

        return webClient.post()
                .uri(oauthUrl + "/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res -> res.bodyToMono(String.class)
                        .flatMap(body -> {
                            log.warn("Google Token API Client Error (4xx): status={}, body={}", res.statusCode(), body);
                            return Mono.error(new ExternalApiClientException(ExternalApiErrorCode.FAILED_SOCIAL_API, "잘못된 구글 인증 요청입니다.: " + body));
                        }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> res.bodyToMono(String.class)
                        .flatMap(body -> {
                            log.error("Google Token API Server Error (5xx): status={}, body={}", res.statusCode(), body);
                            return Mono.error(new ExternalApiException(ExternalApiErrorCode.FAILED_SOCIAL_API, "Google 서버 에러: " + body));
                        }))
                .bodyToMono(GoogleTokenResponse.class)
                .timeout(Duration.ofSeconds(2))
                .transformDeferred(CircuitBreakerOperator.of(googleTokenCircuitBreaker));
    }

    private Mono<GoogleUserInfoResponse> fetchUserInfo(String accessToken) {
        return webClient.get()
                .uri(apiUrl + "/oauth2/v3/userinfo")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res -> res.bodyToMono(String.class)
                        .flatMap(body -> {
                            log.warn("Google UserInfo API Client Error (4xx): status={}, body={}", res.statusCode(), body);
                            return Mono.error(new ExternalApiClientException(ExternalApiErrorCode.FAILED_SOCIAL_API, "잘못된 사용자 정보 요청입니다.: " + body));
                        }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> res.bodyToMono(String.class)
                        .flatMap(body -> {
                            log.error("Google UserInfo API Server Error (5xx): status={}, body={}", res.statusCode(), body);
                            return Mono.error(new ExternalApiException(ExternalApiErrorCode.FAILED_SOCIAL_API, "Google 서버 에러: " + body));
                        }))
                .bodyToMono(GoogleUserInfoResponse.class)
                .timeout(Duration.ofSeconds(2))
                .transformDeferred(CircuitBreakerOperator.of(googleApiCircuitBreaker));
    }

    @Override
    public Mono<Boolean> unlink(OAuthProvider provider, String oauthId, String oauthRefreshToken) {
        if (oauthRefreshToken == null || oauthRefreshToken.isBlank()) {
            throw new ExternalApiException(ExternalApiErrorCode.INVALID_REFRESH_TOKEN, "구글 리프레시 토큰이 없어 연동 해제가 불가능합니다.");
        }

        return webClient.post()
                .uri(URI.create(oauthUrl + "/revoke"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("token", oauthRefreshToken)) // 리프레시 토큰 전송
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res -> res.bodyToMono(String.class)
                        .flatMap(body -> {
                            log.warn("Google Unlink API Client Error (4xx): status={}, body={}", res.statusCode(), body);
                            return Mono.error(new ExternalApiClientException(ExternalApiErrorCode.FAILED_SOCIAL_API, "잘못된 연동 해제 요청입니다.: " + body));
                        }))
                .onStatus(HttpStatusCode::is5xxServerError, res -> res.bodyToMono(String.class)
                        .flatMap(body -> {
                            log.error("Google Unlink API Server Error (5xx): status={}, body={}", res.statusCode(), body);
                            return Mono.error(new ExternalApiException(ExternalApiErrorCode.FAILED_SOCIAL_API, "Google 서버 에러: " + body));
                        }))
                .toBodilessEntity()
                .map(response -> true)
                .defaultIfEmpty(false)
                .timeout(Duration.ofSeconds(2))
                .transformDeferred(CircuitBreakerOperator.of(googleTokenCircuitBreaker))
                .onErrorResume(throwable -> fallbackHandler.fallbackUnlink(provider, oauthId, oauthRefreshToken, throwable));
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    public String getLoginUrl() {
        return UriComponentsBuilder.fromHttpUrl("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "email profile")
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .build()
                .toUriString();
    }
}