package com.serverbe.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * @responsibility 스레드 점유 측정에 필요한 경로만 <b>인증 없이</b> 열어 줍니다.
 * @implSpec {@code bench} 프로파일에서만 빈이 만들어집니다. 프로파일이 없으면 이 클래스는 존재하지
 * 않는 것과 같고, {@link SecurityConfig}의 정책이 그대로 유일한 정책입니다.
 * @implNote <b>왜 별도 체인인가</b> — 측정 대상 경로를 {@link SecurityConfig}의 {@code permitAll}
 * 목록에 끼워 넣으면 상용 정책에 측정용 구멍이 남습니다. 프로파일 조건을 그 목록에 다는 방법도
 * 있지만, 그때는 "이 배열이 환경마다 다르다"는 사실을 읽는 사람이 알아채기 어렵습니다.
 * 체인을 분리하면 <b>파일이 곧 프로파일 경계</b>가 됩니다.
 * @implNote {@code securityMatcher} 로 대상 경로를 좁혔고, 우선순위를 가장 높게 두어 그 세 경로만
 * 이 체인이 처리합니다. 나머지 요청은 전부 {@link SecurityConfig}의 체인으로 내려갑니다.
 * @implNote 지표 엔드포인트를 여는 것은 측정의 전제입니다. 톰캣 워커 점유는 부하 도구가 볼 수 없고
 * {@code /actuator/metrics} 를 긁어야만 보입니다. 측정 절차는
 * {@code docs/benchmark/01-thread-occupancy.md}.
 */
@Configuration
@Profile("bench")
public class BenchSecurityConfig {

    /** 벤치 A/B 경로, 같은 조건에 둔 상용 지오코딩 경로, 그리고 점유량을 읽을 지표 경로. */
    private static final String[] BENCH_PATHS = {
            "/bench/**",
            "/api/v1/geocode",
            "/actuator/metrics/**"
    };

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain benchFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(BENCH_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 논블로킹 경로는 비동기 서블릿으로 디스패치된다. ASYNC 디스패치를 허용하지 않으면
                        // 재진입 시점에 인증이 다시 요구되어 /bench/reactive 만 401 이 된다.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        .anyRequest().permitAll()
                );

        return http.build();
    }
}
