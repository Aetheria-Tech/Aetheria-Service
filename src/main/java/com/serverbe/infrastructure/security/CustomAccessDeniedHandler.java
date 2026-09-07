package com.serverbe.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.adapter.in.web.response.RestApiResponse;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * @responsibility 인증은 되었으나 특정 리소스에 접근할 <b>권한(Role/Authority)이 부족한 경우</b> 발생하는 403 Forbidden 에러를 처리합니다.
 * @implSpec Spring Security의 {@link AccessDeniedHandler} 인터페이스를 구현하며, 시스템 공통 응답 규격인 {@link RestApiResponse}를 JSON 형태로 반환합니다.
 */
@Component
@RequiredArgsConstructor
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * @param request               예외가 발생한 클라이언트의 요청 객체
     * @param response              응답을 작성할 서버의 응답 객체
     * @param accessDeniedException 발생한 인가 예외
     * @throws IOException 응답 스트림 작성 중 오류 발생 시
     * @responsibility 접근 거부 예외 발생 시, HTTP 응답 상태 코드를 403으로 설정하고 에러 메시지를 응답 바디에 작성합니다.
     * @implNote 브라우저나 클라이언트가 에러 구조를 파악할 수 있도록 <b>Content-Type을 application/json</b>으로 강제하며, {@link AuthErrorCode#ACCESS_DENIED}를 사용합니다.
     */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        var apiResponse = RestApiResponse.fail(AuthErrorCode.ACCESS_DENIED, "해당 리소스에 대한 접근 권한이 없습니다.");

        // ObjectMapper를 사용하여 객체를 JSON 문자열로 직렬화 후 응답
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }
}