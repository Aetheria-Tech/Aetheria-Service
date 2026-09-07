package com.serverbe.infrastructure.security;

import com.serverbe.adapter.in.web.filter.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * @responsibility 애플리케이션의 <b>전역 보안 정책</b>을 설정하며, 인증 및 인가 흐름을 제어합니다.
 * @implSpec JWT 기반의 <b>Stateless</b> 인증 방식을 채택하며, 특정 API 경로에 대한 접근 권한을 관리합니다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] SWAGGER_PATHS = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs.yaml"
    };

    private static final String[] LOGIN_PATHS = {
            "/api/v1/auth/login/**",
            "/api/v1/auth/callback/**"
    };

    private static final String[] TOKEN_PATHS = {
            "/api/v1/auth/reissue"
    };

    private static final String[] ACTUATOR_PATHS = {
            "/actuator/health",
            // 로드밸런서가 보는 /actuator/health/alb 같은 헬스 그룹까지 열어 둔다.
            // 정확히 일치만 허용하면 그룹 경로가 401 이 되어 ALB 가 태스크를 계속 unhealthy 로 판정한다.
            "/actuator/health/**"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    /**
     * @return {@link BCryptPasswordEncoder} 인스턴스
     * @responsibility 사용자 비밀번호를 안전하게 암호화하기 위한 {@link PasswordEncoder}를 빈으로 등록합니다.
     * @implNote 강력한 해시 함수인 <b>BCrypt</b> 알고리즘을 사용하며, 무작위 솔트(Salt)를 결합하여 레인보우 테이블 공격을 방어합니다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * @param http 보안 설정을 위한 {@link HttpSecurity}
     * @return 구성된 {@link SecurityFilterChain}
     * @throws Exception 설정 과정에서 오류 발생 시
     * @responsibility HTTP 요청에 대한 보안 필터 체인을 구성합니다.
     * @implNote 1. <b>무상태성(Stateless)</b>: 서버 세션을 사용하지 않으므로 CSRF, FormLogin, HttpBasic을 비활성화하고 세션 정책을 {@link SessionCreationPolicy#STATELESS}로 설정합니다.<br>
     * 2. <b>예외 처리</b>: 인증 실패 시 {@link CustomAuthenticationEntryPoint}, 인가 실패 시 {@link CustomAccessDeniedHandler}가 동작하도록 위임합니다.<br>
     * 3. <b>인가 정책</b>: OAuth 로그인, 토큰 재발급, Swagger 문서는 모든 접근을 허용하며, 그 외 비즈니스 API는 인증된 유저만 접근 가능합니다.<br>
     * 4. <b>필터 배치</b>: 폼 로그인 필터 앞단에 {@link JwtAuthenticationFilter}를 배치하여 JWT 토큰 검증을 우선적으로 수행합니다.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF 및 세션 관리 설정 (Stateless)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint) // 401 에러 핸들링
                        .accessDeniedHandler(accessDeniedHandler)           // 403 에러 핸들링
                )


                // HTTP 요청 권한 설정
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        // 토큰은 인증 없이 접근 가능
                        .requestMatchers(TOKEN_PATHS).permitAll()
                        // Swagger 문서 경로는 모두 허용
                        .requestMatchers(SWAGGER_PATHS).permitAll()
                        // 로그인은 인증 없이 접근 가능
                        .requestMatchers(LOGIN_PATHS).permitAll()
                        .requestMatchers(ACTUATOR_PATHS).permitAll()
                        // 그 외 모든 요청은 인증 필요
                        .anyRequest().authenticated()
                )

                // JWT 필터 배치
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}