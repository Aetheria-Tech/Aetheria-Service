package com.serverbe.infrastructure.config;

import com.serverbe.application.port.out.dto.oauth.OAuthUserInfoResult;
import com.serverbe.application.port.out.oauth.OAuthClientPort;
import com.serverbe.domain.model.user.vo.OAuthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.ResolvableType;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @responsibility OAuth 어댑터들이 제공자별 조회표로 올바르게 조립되는지 확인합니다.
 * @implNote 이 조립이 하는 일의 절반은 <b>중복을 기동 실패로 만드는 것</b>입니다. 리스트를 순회해
 * 첫 일치를 고르던 이전 방식에서는 같은 제공자를 둘이 지원해도 조용히 먼저 등록된 쪽이 이겼고,
 * 그것은 아무 신호도 남기지 않는 종류의 버그였습니다.
 */
@DisplayName("OAuth 클라이언트 조회표 조립")
class OAuthClientConfigTest {

    private final OAuthClientConfig config = new OAuthClientConfig();

    @Test
    @DisplayName("어댑터가 선언한 제공자를 키로 삼아 조회표를 만든다")
    void 어댑터가_선언한_제공자를_키로_삼아_조회표를_만든다() {
        OAuthClientPort kakao = new StubOAuthClient(OAuthProvider.KAKAO);
        OAuthClientPort google = new StubOAuthClient(OAuthProvider.GOOGLE);

        Map<OAuthProvider, OAuthClientPort> clients =
                config.oAuthClientsByProvider(List.of(kakao, google));

        assertThat(clients).containsExactlyInAnyOrderEntriesOf(
                Map.of(OAuthProvider.KAKAO, kakao, OAuthProvider.GOOGLE, google));
    }

    @Test
    @DisplayName("같은 제공자를 둘이 선언하면 기동이 실패한다")
    void 같은_제공자를_둘이_선언하면_기동이_실패한다() {
        List<OAuthClientPort> conflicting =
                List.of(new StubOAuthClient(OAuthProvider.KAKAO), new StubOAuthClient(OAuthProvider.KAKAO));

        assertThatThrownBy(() -> config.oAuthClientsByProvider(conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KAKAO")
                .hasMessageContaining(StubOAuthClient.class.getName());
    }

    /**
     * 스프링은 {@code Map<String, T>} 주입만 "모든 빈을 이름으로 모아 주는" 특례로 처리합니다.
     * 키가 {@link OAuthProvider}라 그 특례가 적용되지 않고 일반 타입 해석으로 이 {@code @Bean}을
     * 찾아야 하는데, <b>그 가정이 깨지면 기동에서야 드러납니다.</b> 실제 부트 컨텍스트 기동 테스트는
     * MySQL·Redis가 필요한 {@code integrationTest}에 있으므로, 스프링 코어만으로 여기서 확인합니다.
     */
    @Test
    @DisplayName("스프링이 비-String 키 Map을 타입으로 주입한다")
    void 스프링이_비String_키_Map을_타입으로_주입한다() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(OAuthClientConfig.class, KakaoStubConfig.class);
            context.refresh();

            ResolvableType mapType = ResolvableType.forClassWithGenerics(
                    Map.class, OAuthProvider.class, OAuthClientPort.class);

            assertThat(context.getBeanNamesForType(mapType)).hasSize(1);

            @SuppressWarnings("unchecked")
            Map<OAuthProvider, OAuthClientPort> clients =
                    (Map<OAuthProvider, OAuthClientPort>) context.getBean("oAuthClientsByProvider");

            assertThat(clients).containsOnlyKeys(OAuthProvider.KAKAO);
        }
    }

    @org.springframework.context.annotation.Configuration
    static class KakaoStubConfig {
        @org.springframework.context.annotation.Bean
        OAuthClientPort kakaoStub() {
            return new StubOAuthClient(OAuthProvider.KAKAO);
        }
    }

    /**
     * 조립만 확인하므로 통신 메서드는 호출되지 않습니다. 선언하는 제공자만이 이 스텁의 내용입니다.
     */
    private record StubOAuthClient(OAuthProvider provider) implements OAuthClientPort {

        @Override
        public Mono<OAuthUserInfoResult> getUserInfo(String code, OAuthProvider provider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Boolean> unlink(OAuthProvider provider, String oauthId, String oauthRefreshToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getLoginUrl() {
            throw new UnsupportedOperationException();
        }
    }
}
