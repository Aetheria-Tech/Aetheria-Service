package com.serverbe.adapter.in.web;

import com.serverbe.adapter.in.web.support.annotation.ExtractAccessToken;
import com.serverbe.adapter.in.web.support.annotation.ExtractDeviceId;
import com.serverbe.adapter.in.web.dto.auth.AccessTokenResponse;
import com.serverbe.adapter.in.web.support.annotation.ExtractRefreshToken;
import com.serverbe.adapter.in.web.response.RestApiResponse;
import com.serverbe.application.port.in.oauth.LogoutUseCase;
import com.serverbe.application.port.in.oauth.LoginUseCase;
import com.serverbe.application.port.in.oauth.WithdrawUseCase;
import com.serverbe.application.port.in.token.ReissueUseCase;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.domain.exception.external.ExternalApiErrorCode;
import com.serverbe.domain.exception.server.ServerErrorCode;
import com.serverbe.domain.exception.server.ServerException;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import com.serverbe.infrastructure.config.properties.JwtProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URI;

/**
 * 인증 및 권한 관련 HTTP 요청을 처리하는 웹 어댑터입니다.
 * 소셜 로그인 시작, 콜백 처리, 토큰 재발급, 로그아웃, 회원 탈퇴를 담당합니다.
 */
@Slf4j
@Tag(name = "Auth", description = "사용자 인증 관련 API")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final LoginUseCase loginUseCase;
    private final WithdrawUseCase withdrawUseCase;
    private final LogoutUseCase logoutUseCase;
    private final ReissueUseCase reissueUseCase;
    private final String refreshTokenCookie;
    private final long refreshTokenCookieExpireSeconds;

    public AuthController(
            LoginUseCase loginUseCase,
            WithdrawUseCase withdrawUseCase,
            LogoutUseCase logoutUseCase,
            ReissueUseCase reissueUseCase,
            JwtProperties jwtProperties
    ) {
        this.loginUseCase = loginUseCase;
        this.withdrawUseCase = withdrawUseCase;
        this.logoutUseCase = logoutUseCase;
        this.reissueUseCase = reissueUseCase;
        this.refreshTokenCookie = jwtProperties.refreshToken().cookie();
        this.refreshTokenCookieExpireSeconds = jwtProperties.refreshToken().expirationDays().toSeconds();
    }

    @Operation(
            summary = "소셜 로그인 리다이렉트",
            description = "선택한 소셜 서비스(Provider)의 로그인 페이지로 사용자를 이동시킵니다.",
            responses = {
                    @ApiResponse(
                            responseCode = "302",
                            description = "소셜 로그인 페이지로 리다이렉트 성공",
                            headers = @Header(name = "Location", description = "이동할 소셜 로그인 URL", schema = @Schema(type = "string"))
                    ),
                    @ApiResponse(responseCode = "500", description = "Internal Server Error")
            }
    )
    @GetMapping("/login/{provider}")
    public Mono<ResponseEntity<Void>> redirectToSocial(
            @Parameter(description = "소셜 로그인 제공자", example = "kakao")
            @PathVariable(value = "provider") OAuthProvider provider
    ) {
        return Mono.fromCallable(() -> loginUseCase.getSocialLoginUrl(provider))
                .map(url -> ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(url))
                        .build());
    }

    @Operation(
            summary = "소셜 로그인 콜백 (인가 코드 처리)",
            description = "소셜 서비스로부터 리다이렉트된 인가 코드(code)를 받아 로그인을 완료하고, 서비스 전용 토큰을 발급합니다.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "로그인 성공",
                            headers = @Header(
                                    name = "Set-Cookie",
                                    description = "리프레시 토큰이 담긴 쿠키 (httpOnly, secure)",
                                    schema = @Schema(type = "string", example = "refresh_token=...; Path=/; HttpOnly; Secure; SameSite=Lax")
                            ),
                            useReturnTypeSchema = true
                    ),
                    @ApiResponse(responseCode = "400", description = "잘못된 인가 코드이거나 유효하지 않은 요청인 경우"),
                    @ApiResponse(responseCode = "500", description = "소셜 서버와의 통신 실패 또는 내부 서버 오류")
            }
    )
    @GetMapping("/callback/{provider}")
    public Mono<ResponseEntity<RestApiResponse<AccessTokenResponse>>> loginCallback(
            @Parameter(description = "소셜 로그인 제공자", example = "kakao")
            @PathVariable(value = "provider") OAuthProvider provider,

            @Parameter(description = "소셜 서버에서 발급한 일회성 인가 코드", required = true)
            @RequestParam("code") @NotBlank String code,

            @ExtractDeviceId String deviceId,
            @Parameter(hidden = true) HttpServletResponse response
    ) {
        // 1. 인가 코드로 소셜 서버와 통신하여 유저 정보 획득
        // 2. 신규 유저면 가입, 기존 유저면 정보 업데이트(Upsert)
        // 3. 우리 서비스 전용 액세스/리프레시 토큰 발급 및 리프레시 토큰 Redis 저장
        return loginUseCase.login(code, provider, deviceId)
                .map(tokenResponse -> {
                    addCookieToResponse(response, tokenResponse.refreshTokenResult().opaqueToken(), refreshTokenCookieExpireSeconds);
                    return ResponseEntity.ok(RestApiResponse.success(AccessTokenResponse.toResponse(tokenResponse.accessTokenResult())));
                });
    }

    @Operation(
            summary = "회원 탈퇴 (계정 삭제)",
            description = "현재 로그인한 사용자의 계정을 삭제하고 소셜 서비스와의 연동을 해제(Unlink)합니다.",
            security = @SecurityRequirement(name = "jwtAuth"),
            responses = {
                    @ApiResponse(responseCode = "204", description = "탈퇴 성공"),
                    @ApiResponse(responseCode = "401", description = "인증 실패"),
                    @ApiResponse(responseCode = "500", description = "서버 오류")
            }
    )
    @DeleteMapping("/me")
    public Mono<ResponseEntity<RestApiResponse<Void>>> withdraw(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        // DB 삭제 + 소셜 연동 해제(Unlink) + Redis 세션 삭제를 수행
        return withdrawUseCase.withdraw(userId).map(success -> {
            if (success) {
                return ResponseEntity.status(HttpStatus.NO_CONTENT).body(RestApiResponse.noContent());
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(RestApiResponse.fail(ExternalApiErrorCode.FAILED_SOCIAL_API));
        });
    }

    @Operation(
            summary = "로그아웃",
            description = "현재 사용 중인 Access Token과 Refresh Token을 무효화하고, 브라우저에 저장된 리프레시 토큰 쿠키를 제거합니다.",
            security = @SecurityRequirement(name = "jwtAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "로그아웃 성공",
                            headers = @Header(
                                    name = "Set-Cookie",
                                    description = "리프레시 토큰 쿠키 삭제 (Max-Age=0)",
                                    schema = @Schema(type = "string", example = "refresh_token=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax")
                            )
                    ),
                    @ApiResponse(responseCode = "401", description = "이미 로그아웃되었거나 유효하지 않은 토큰입니다.")
            }
    )
    @PostMapping("/logout")
    public ResponseEntity<RestApiResponse<Void>> logout(
            @Parameter(hidden = true) HttpServletResponse response,
            @ExtractAccessToken String accessToken,
            @ExtractRefreshToken String refreshToken,
            @ExtractDeviceId String deviceId
    ) {
        logoutUseCase.logout(accessToken, refreshToken, deviceId);
        addCookieToResponse(response, "", 0);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(RestApiResponse.noContent());
    }

    @Operation(
            summary = "전역 로그아웃 (모든 기기 로그아웃)",
            description = "현재 사용 중인 계정의 모든 세션을 비활성화합니다. 호출 시 모든 기기에서 토큰이 무효화되며, 현재 브라우저의 리프레시 토큰 쿠키도 삭제됩니다.",
            security = @SecurityRequirement(name = "jwtAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "전역 로그아웃 성공",
                            headers = @Header(
                                    name = "Set-Cookie",
                                    description = "리프레시 토큰 쿠키 삭제 (Max-Age=0)",
                                    schema = @Schema(type = "string", example = "refresh_token=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax")
                            )
                    ),
                    @ApiResponse(responseCode = "401", description = "인증 실패 또는 유효하지 않은 토큰")
            }
    )
    @PostMapping("/logout/all")
    public ResponseEntity<RestApiResponse<Void>> globalLogout(
            @ExtractAccessToken String accessToken,
            @Parameter(hidden = true) HttpServletResponse response
    ) {
        logoutUseCase.globalLogout(accessToken);
        addCookieToResponse(response, "", 0);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(RestApiResponse.noContent());
    }

    @Operation(
            summary = "토큰 재발급 (Refresh Token Rotation)",
            description = "만료된 Access Token과 유효한 Refresh Token을 사용하여 새로운 토큰 세트(Access/Refresh)를 발급받습니다. " +
                    "RTR 정책에 따라 기존 리프레시 토큰은 더 이상 사용할 수 없으며, 새 리프레시 토큰이 쿠키로 설정됩니다.",
            security = @SecurityRequirement(name = "jwtAuth"),
            parameters = {
                    @Parameter(
                            name = "X-Device-Id",
                            description = "기기 고유 식별자 (모바일 앱의 경우 UUID 등 고유값 권장). 이 값이 존재하면 최우선으로 기기 식별에 사용됩니다.",
                            in = ParameterIn.HEADER
                    ),
                    @Parameter(
                            name = "User-Agent",
                            description = "클라이언트 브라우저/기기 정보. X-Device-Id가 없을 경우, 이 값을 해싱하여 기기 식별자로 사용합니다. (브라우저는 자동 전송)",
                            in = ParameterIn.HEADER
                    )
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "재발급 성공",
                            headers = @Header(
                                    name = "Set-Cookie",
                                    description = "새로 발급된 리프레시 토큰 쿠키 (HttpOnly, Secure)",
                                    schema = @Schema(type = "string", example = "refresh_token=new_token_value; Path=/; HttpOnly; Secure; SameSite=Lax")
                            ),
                            useReturnTypeSchema = true
                    ),
                    @ApiResponse(responseCode = "400", description = "리프레시 토큰이 누락되었거나 형식이 잘못된 경우"),
                    @ApiResponse(responseCode = "401", description = "리프레시 토큰이 만료되었거나 변조되어 재로그인이 필요한 경우")
            }
    )
    @PostMapping("/reissue")
    public Mono<ResponseEntity<RestApiResponse<AccessTokenResponse>>> reissue(
            @ExtractDeviceId String deviceId,
            @ExtractAccessToken String accessToken,
            @ExtractRefreshToken String refreshToken,
            @Parameter(hidden = true) HttpServletResponse response
    ) {
        if (!StringUtils.hasText(refreshToken)) {
            return Mono.error(new AuthException(AuthErrorCode.JWT_TOKEN_IS_EMPTY));
        }

        return Mono.fromCallable(() -> reissueUseCase.reissue(accessToken, refreshToken, deviceId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(tokenResponse -> {
                    addCookieToResponse(response, tokenResponse.refreshTokenResult().opaqueToken(), refreshTokenCookieExpireSeconds);
                    return ResponseEntity.ok(RestApiResponse.success(AccessTokenResponse.toResponse(tokenResponse.accessTokenResult())));
                });
    }

    private void addCookieToResponse(HttpServletResponse response, String token, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(refreshTokenCookie, token)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(maxAgeSeconds)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}