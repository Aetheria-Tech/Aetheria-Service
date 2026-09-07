package com.serverbe.adapter.in.web.filter;

import com.serverbe.application.port.out.security.TokenResolver;
import com.serverbe.application.port.out.security.dto.JwtPayloadDto;
import com.serverbe.application.port.out.token.TokenBlacklistPort;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.domain.model.user.vo.Role;
import com.serverbe.infrastructure.security.TokenExtractor;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * @responsibility 웹 어댑터가 조립하는 인증 객체의 형태를 고정합니다.
 * @implSpec principal이 {@code Long}이 아니면 {@code RateLimitAspect}의 캐스팅이 깨지고,
 * 권한 문자열에 {@code ROLE_} 접두사가 붙으면 향후 {@code hasRole(...)} 규칙과 어긋납니다.
 * 둘 다 컴파일로는 잡히지 않으므로 테스트가 대신 봅니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JWT 인증 필터")
class JwtAuthenticationFilterTest {

    @Mock
    private TokenResolver tokenResolver;
    @Mock
    private TokenBlacklistPort tokenBlacklistPort;
    @Mock
    private TokenExtractor tokenExtractor;
    @Mock
    private HandlerExceptionResolver handlerExceptionResolver;
    @Mock
    private FilterChain filterChain;

    private static final String TOKEN = "valid.access.token";

    private JwtAuthenticationFilter filter() {
        return new JwtAuthenticationFilter(
                tokenResolver, tokenBlacklistPort, tokenExtractor, handlerExceptionResolver);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("유효한 토큰이면 principal에 Long userId를, 권한에 접두사 없는 Role 이름을 담는다")
    void 유효한_토큰이면_principal에_Long_userId를_담는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(tokenExtractor.extractAccessToken(request)).willReturn(TOKEN);
        given(tokenResolver.validateAccessToken(TOKEN)).willReturn(true);
        given(tokenBlacklistPort.isAccessTokenBlacklisted(TOKEN)).willReturn(false);
        given(tokenResolver.resolvePayload(TOKEN)).willReturn(new JwtPayloadDto(42L, Role.USER));

        filter().doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isInstanceOf(Long.class).isEqualTo(42L);
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("USER");

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("블랙리스트에 오른 토큰이면 인증하지 않고 예외를 핸들러로 넘긴다")
    void 블랙리스트_토큰이면_인증하지_않고_예외를_넘긴다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(tokenExtractor.extractAccessToken(request)).willReturn(TOKEN);
        given(tokenResolver.validateAccessToken(TOKEN)).willReturn(true);
        given(tokenBlacklistPort.isAccessTokenBlacklisted(TOKEN)).willReturn(true);

        filter().doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(tokenResolver, never()).resolvePayload(any());
        verify(filterChain, never()).doFilter(any(), any());

        ArgumentCaptor<Exception> thrown = ArgumentCaptor.forClass(Exception.class);
        verify(handlerExceptionResolver).resolveException(eq(request), eq(response), isNull(), thrown.capture());
        assertThat(thrown.getValue())
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.JWT_TOKEN_IS_LOGOUT);
    }

    @Test
    @DisplayName("토큰이 없으면 SecurityContext를 비운 채 체인을 계속 진행한다")
    void 토큰이_없으면_컨텍스트를_비운_채_체인을_계속한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(tokenExtractor.extractAccessToken(request)).willReturn(null);

        filter().doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
        verify(handlerExceptionResolver, never()).resolveException(any(), any(), any(), any());
    }

    @Test
    @DisplayName("서명이 유효하지 않은 토큰이면 인증하지 않고 체인을 계속 진행한다")
    void 서명이_유효하지_않으면_인증하지_않는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(tokenExtractor.extractAccessToken(request)).willReturn(TOKEN);
        given(tokenResolver.validateAccessToken(TOKEN)).willReturn(false);

        filter().doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(tokenBlacklistPort, never()).isAccessTokenBlacklisted(any());
        verify(filterChain).doFilter(request, response);
    }
}
