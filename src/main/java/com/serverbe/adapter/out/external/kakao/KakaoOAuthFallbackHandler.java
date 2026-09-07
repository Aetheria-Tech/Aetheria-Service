package com.serverbe.adapter.out.external.kakao;

import com.serverbe.application.port.out.dto.oauth.OAuthUserInfoResult;
import com.serverbe.domain.exception.external.ExternalApiErrorCode;
import com.serverbe.domain.exception.external.ExternalApiException;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 카카오 OAuth API 통신 실패 시 대체(Fallback) 동작을 처리하는 핸들러
 */
@Slf4j
@Component
public class KakaoOAuthFallbackHandler {

    public Mono<OAuthUserInfoResult> fallbackGetUserInfo(String code, OAuthProvider provider, Throwable t) {
        log.error("🚨 [CircuitBreaker/Timeout] 카카오 로그인 API 장애 발생: {}", t.getMessage());
        return Mono.error(new ExternalApiException(
                ExternalApiErrorCode.FAILED_SOCIAL_API,
                "현재 카카오 로그인 서버의 응답이 지연되고 있습니다. 잠시 후 다시 시도해주세요."
        ));
    }

    public Mono<Boolean> fallbackUnlink(OAuthProvider provider, String oauthId, String oauthRefreshToken, Throwable t) {
        log.error("🚨 [CircuitBreaker/Timeout] 카카오 연동 해제 API 장애 발생: {}", t.getMessage());
        return Mono.error(new ExternalApiException(
                ExternalApiErrorCode.FAILED_SOCIAL_API,
                "카카오 서버 지연으로 인해 연동 해제에 실패했습니다."
        ));
    }
}