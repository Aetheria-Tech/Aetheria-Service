package com.serverbe.adapter.in.web.filter;


import com.serverbe.application.port.out.security.TokenResolver;
import com.serverbe.application.port.out.security.dto.JwtPayloadDto;
import com.serverbe.application.port.out.token.TokenBlacklistPort;
import com.serverbe.domain.exception.BusinessException;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.infrastructure.security.TokenExtractor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.List;

/**
 * @responsibility 모든 HTTP 요청의 <b>인증 헤더</b>를 검사하여 유효한 사용자일 경우 <b>SecurityContext</b>에 인증 정보를 등록합니다.
 * @implSpec {@link OncePerRequestFilter}를 확장하여 요청당 한 번의 실행을 보장하며, 블랙리스트 확인을 통한 <b>Fail-Fast</b> 보안 전략을 수행합니다.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenResolver tokenResolver;
    private final TokenBlacklistPort tokenBlacklistPort;
    private final TokenExtractor tokenExtractor;
    private final HandlerExceptionResolver handlerExceptionResolver;

    /**
     * @implNote 필터에서 발생하는 예외를 스프링 MVC의 예외 처리 메커니즘으로 전달하기 위해 {@link HandlerExceptionResolver}를 주입받습니다.
     */
    public JwtAuthenticationFilter(
            TokenResolver tokenResolver,
            TokenBlacklistPort tokenBlacklistPort,
            TokenExtractor tokenExtractor,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.tokenResolver = tokenResolver;
        this.tokenBlacklistPort = tokenBlacklistPort;
        this.handlerExceptionResolver = handlerExceptionResolver;
        this.tokenExtractor = tokenExtractor;
    }

    /**
     * @param request     HTTP 요청 객체
     * @param response    HTTP 응답 객체
     * @param filterChain 서블릿 필터 체인
     * @throws BusinessException 토큰이 블랙리스트에 등록되어 있거나 인증 과정에서 문제가 발생할 경우 발생
     * @responsibility 요청에서 토큰을 추출하고, 유효성 검증 및 블랙리스트 대조 후 인증 객체를 등록합니다.
     * @implSpec 1. <b>토큰 추출</b>: {@link TokenExtractor}를 통해 헤더에서 Bearer 토큰을 가져옵니다.<br>
     * 2. <b>블랙리스트 검사</b>: 유효한 토큰이라도 이미 로그아웃된 토큰인지 {@link TokenBlacklistPort#isAccessTokenBlacklisted(String)}로 확인합니다.<br>
     * 3. <b>인증 등록</b>: 모든 검증이 완료되면 {@link SecurityContextHolder}에 인증 정보를 저장합니다.
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) {
        String token = tokenExtractor.extractAccessToken(request);

        try {
            if (StringUtils.hasText(token) && tokenResolver.validateAccessToken(token)) {

                // 1. 블랙리스트 확인을 인증 객체 등록보다 먼저 수행 (Fail-Fast)
                if (tokenBlacklistPort.isAccessTokenBlacklisted(token)) {
                    log.warn("[JWT Filter] 로그아웃된 토큰으로 접근 시도: {}", token);
                    throw new AuthException(AuthErrorCode.JWT_TOKEN_IS_LOGOUT);
                }

                // 2. 모든 검증을 마친 후 인증 객체 등록
                SecurityContextHolder.getContext().setAuthentication(toAuthentication(token));
            }

            filterChain.doFilter(request, response);

        } catch (Exception e) {
            log.error("[JWT Filter Exception] -> ", e);
            handlerExceptionResolver.resolveException(request, response, null, e);
        }
    }

    /**
     * @param accessToken 검증이 끝난 액세스 토큰
     * @return Spring Security 인증 객체
     * @responsibility 포트가 돌려준 페이로드를 프레임워크의 인증 객체로 조립합니다.
     * @implSpec principal은 반드시 {@code Long userId}여야 합니다. 컨트롤러들이
     * {@code @AuthenticationPrincipal Long userId}로 받고 {@code RateLimitAspect}가
     * {@code (Long) getPrincipal()}로 캐스팅합니다. 권한 문자열도 {@code ROLE_} 접두사 없는
     * {@code role.name()} 그대로여야 합니다.
     * @implNote 이 조립이 어댑터에 있는 이유는 Spring Security가 웹 계층의 기술이기 때문입니다.
     * 이전에는 {@code TokenResolver} 포트가 {@code Authentication}을 직접 반환했습니다.
     */
    private Authentication toAuthentication(String accessToken) {
        JwtPayloadDto payload = tokenResolver.resolvePayload(accessToken);

        return new UsernamePasswordAuthenticationToken(
                payload.userId(),
                null,
                List.of(new SimpleGrantedAuthority(payload.role().name()))
        );
    }
}
