package com.serverbe.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.adapter.in.web.response.RestApiResponse;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * @responsibility 인증되지 않은 사용자가 보호된 리소스에 접근하려고 할 때 발생하는 <b>401 Unauthorized</b> 에러를 처리합니다.
 * @implSpec Spring Security의 {@link AuthenticationEntryPoint} 인터페이스를 구현하며, 로그인되지 않은 상태에서의 요청을 차단하고 공통 응답 규격({@link RestApiResponse})을 반환합니다.
 */
@Component
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    /**
     * @param request       인증 실패가 발생한 요청 객체
     * @param response      응답을 작성할 객체
     * @param authException 발생한 인증 관련 예외
     * @throws IOException 응답 스트림 작성 중 오류 발생 시
     * @responsibility 인증 예외 발생 시, HTTP 응답 상태 코드를 401로 설정하고 클라이언트에게 인증 필요 메시지를 전달합니다.
     * @implNote 1. <b>Filter Chain</b> 레벨에서 동작하므로, 스프링 MVC의 @ControllerAdvice가 아닌 직접 HttpServletResponse에 작성합니다.<br>
     * 2. 응답 규격의 일관성을 위해 {@link RestApiResponse#fail} 메서드를 사용하여 결과를 구조화합니다.
     */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        // ApiResponse.fail 형식을 그대로 유지하여 클라이언트에게 전달
        var apiResponse = RestApiResponse.fail(AuthErrorCode.UNAUTHORIZED, "인증이 필요합니다.");

        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }
}