package com.serverbe.infrastructure.config;

import com.serverbe.application.port.out.oauth.OAuthClientPort;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @responsibility 스프링이 모아 준 {@link OAuthClientPort} 구현체들을 <b>제공자별 조회표</b>로 조립합니다.
 * @implSpec 애플리케이션 서비스는 {@code List<OAuthClientPort>}를 받지 않습니다. "이 제공자를 누가
 * 담당하는가"의 답은 기동 시점에 이미 정해져 있고 절대 바뀌지 않으므로, <b>런타임에 반복할 이유가
 * 없는 탐색</b>입니다. 그 탐색을 여기서 한 번 끝내고 서비스에는 완성된 {@link Map}을 넘깁니다.
 * @implNote 이 클래스가 애플리케이션이 아니라 인프라에 있는 이유는 {@code @Configuration}과
 * {@code @Bean}이 스프링 타입이기 때문입니다. {@code LayerDependencyTest}의
 * {@code 애플리케이션은_포트와_도메인_안에서만_논다}가 애플리케이션 계층에서 이 애노테이션들을
 * 금지합니다. {@code ApplicationPolicyConfig}와 같은 역할 — <b>인프라가 조립하고 애플리케이션은
 * 결과만 받습니다.</b>
 * @implNote 스프링은 {@code Map<String, T>} 주입만 "모든 빈을 이름으로 모아 주는" 특례로 처리합니다.
 * 키가 {@link OAuthProvider}이므로 그 특례가 적용되지 않고 <b>일반 타입 해석으로 이 빈을 찾습니다.</b>
 */
@Configuration
public class OAuthClientConfig {

    /**
     * @param clients 스프링이 수집한 모든 {@link OAuthClientPort} 구현체
     * @return 제공자를 키로 갖는 불변 조회표
     * @responsibility 각 어댑터가 {@link OAuthClientPort#provider()}로 선언한 값을 키로 삼아 조회표를 만듭니다.
     * @throws IllegalStateException 두 어댑터가 같은 제공자를 선언한 경우
     * @implNote 중복을 <b>기동 실패로 만드는 것</b>이 이 메서드의 절반입니다. 리스트를 순회해 첫
     * 일치를 고르던 이전 방식에서는 같은 제공자를 둘이 지원해도 <b>조용히 먼저 등록된 쪽이 이겼고</b>,
     * 어느 쪽이 이겼는지는 빈 등록 순서에 달려 있어 예측할 수 없었습니다.
     */
    @Bean
    public Map<OAuthProvider, OAuthClientPort> oAuthClientsByProvider(List<OAuthClientPort> clients) {
        return clients.stream().collect(Collectors.toUnmodifiableMap(
                OAuthClientPort::provider,
                Function.identity(),
                (first, second) -> {
                    throw new IllegalStateException(String.format(
                            "같은 OAuthProvider(%s)를 두 어댑터가 선언했습니다: %s, %s",
                            first.provider(), first.getClass().getName(), second.getClass().getName()));
                }));
    }
}
