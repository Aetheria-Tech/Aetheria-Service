package com.serverbe.infrastructure.config.aop;

import com.serverbe.application.annotation.RateLimit;
import com.serverbe.application.service.RateLimiterService;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.auth.AuthException;
import com.serverbe.domain.exception.server.RateLimitExceededException;
import com.serverbe.domain.exception.server.ServerErrorCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link RateLimitAspect}는 {@code @within(RestController)}로 가로챈 메서드의
 * {@code @RateLimit} 애노테이션(실제 리플렉션 값)과 {@link SecurityContextHolder}에 등록된
 * 인증 정보를 사용해 동작하므로, 테스트도 Mockito로 애노테이션 자체를 mocking하는 대신
 * 실제로 {@code @RateLimit}이 붙은 더미 메서드를 리플렉션으로 조회해 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

    @InjectMocks
    private RateLimitAspect rateLimitAspect;

    @Mock
    private RateLimiterService rateLimiterService;

    @Mock
    private ProceedingJoinPoint joinPoint; // AOP 진행을 위한 Mock

    private MockHttpServletRequest request;

    private static final String TEST_ENDPOINT = "/api/v1/test";
    private static final Long TEST_USER_ID = 100L;

    /**
     * 실제 {@code @RateLimit} 애노테이션이 붙은 더미 메서드 모음.
     * RateLimitAspect가 {@code method.getAnnotationsByType(RateLimit.class)}로 리플렉션 조회를
     * 하기 때문에, Mockito로 애노테이션 인스턴스 자체를 mocking할 수 없어 실제 메서드가 필요합니다.
     */
    private static class RateLimitedEndpoints {
        @RateLimit(target = RateLimit.TargetType.USER, capacity = 10, refillRate = 1, retryAfterSeconds = 60)
        void userLimited() {}

        @RateLimit(target = RateLimit.TargetType.IP, capacity = 5, refillRate = 1, retryAfterSeconds = 120)
        void ipLimited() {}
    }

    @BeforeEach
    void setUp() {
        // 1. Mock Request 세팅 및 URI 지정
        request = new MockHttpServletRequest();
        request.setRequestURI(TEST_ENDPOINT);

        // 2. RequestContextHolder에 Request 주입 (AOP 내부에서 꺼내 쓸 수 있도록)
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        // 다른 테스트에 영향을 주지 않도록 초기화 (SecurityContext는 스레드로컬이라 반드시 비워야 함)
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private void givenMethodAnnotatedWith(String methodName) throws NoSuchMethodException {
        Method method = RateLimitedEndpoints.class.getDeclaredMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        given(signature.getMethod()).willReturn(method);
        given(joinPoint.getSignature()).willReturn(signature);
    }

    private void givenAuthenticatedUser(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of())
        );
    }

    @Test
    @DisplayName("인증된 유저이고 User 기반 허용량 이내라면 통과한다")
    void checkRateLimit_User_Allowed_Pass() throws Throwable {
        // given
        givenMethodAnnotatedWith("userLimited");
        givenAuthenticatedUser(TEST_USER_ID);

        given(rateLimiterService.isAllowedForUser(TEST_USER_ID, TEST_ENDPOINT, 10, 1)).willReturn(true);

        Object expectedResponse = new Object();
        given(joinPoint.proceed()).willReturn(expectedResponse);

        // when
        Object result = rateLimitAspect.checkRateLimit(joinPoint);

        // then
        assertThat(result).isEqualTo(expectedResponse);
        verify(rateLimiterService).isAllowedForUser(TEST_USER_ID, TEST_ENDPOINT, 10, 1);
    }

    @Test
    @DisplayName("User 기반 제한인데 인증 정보가 없다면 AuthException이 발생한다")
    void checkRateLimit_User_NoAuthentication_ThrowsAuthException() throws NoSuchMethodException {
        // given: SecurityContext에 인증 정보를 세팅하지 않음 (비로그인 상태)
        givenMethodAnnotatedWith("userLimited");

        // when & then
        assertThatThrownBy(() -> rateLimitAspect.checkRateLimit(joinPoint))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("User 기반 제한 초과 시 RateLimitExceededException이 발생한다")
    void checkRateLimit_User_Blocked_ThrowsException() throws NoSuchMethodException {
        // given
        givenMethodAnnotatedWith("userLimited");
        givenAuthenticatedUser(TEST_USER_ID);

        // 차단 상황 설정 (isAllowed -> false)
        given(rateLimiterService.isAllowedForUser(TEST_USER_ID, TEST_ENDPOINT, 10, 1)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> rateLimitAspect.checkRateLimit(joinPoint))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(exception -> {
                    RateLimitExceededException e = (RateLimitExceededException) exception;
                    assertThat(e.getErrorCode()).isEqualTo(ServerErrorCode.TOO_MANY_REQUESTS);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(60); // userLimited()에 선언된 retryAfterSeconds
                });
    }

    @Test
    @DisplayName("허용된 IP라면 통과한다")
    void checkRateLimit_Ip_Allowed_Pass() throws Throwable {
        // given
        String clientIp = "203.0.113.10";
        request.addHeader("X-Forwarded-For", clientIp);
        request.setRemoteAddr(clientIp);

        givenMethodAnnotatedWith("ipLimited");

        given(rateLimiterService.isAllowedForIp(clientIp, TEST_ENDPOINT, 5, 1)).willReturn(true);

        Object expectedResponse = new Object();
        given(joinPoint.proceed()).willReturn(expectedResponse);

        // when
        Object result = rateLimitAspect.checkRateLimit(joinPoint);

        // then
        assertThat(result).isEqualTo(expectedResponse);
    }

    @Test
    @DisplayName("IP 기반 제한 초과 시 RateLimitExceededException이 발생한다")
    void checkRateLimit_Ip_Blocked_ThrowsException() throws NoSuchMethodException {
        // given
        String clientIp = "203.0.113.10";
        request.addHeader("X-Forwarded-For", clientIp);
        request.setRemoteAddr(clientIp);

        givenMethodAnnotatedWith("ipLimited");

        // 차단 상황 설정 (isAllowed -> false)
        given(rateLimiterService.isAllowedForIp(clientIp, TEST_ENDPOINT, 5, 1)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> rateLimitAspect.checkRateLimit(joinPoint))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(exception -> {
                    RateLimitExceededException e = (RateLimitExceededException) exception;
                    assertThat(e.getErrorCode()).isEqualTo(ServerErrorCode.TOO_MANY_REQUESTS);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(120); // ipLimited()에 선언된 retryAfterSeconds
                });
    }
}
