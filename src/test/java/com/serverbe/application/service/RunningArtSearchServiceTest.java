package com.serverbe.application.service;

import com.serverbe.application.config.ArtSearchPolicy;
import com.serverbe.application.port.in.dto.art.RunningArtResult;
import com.serverbe.application.port.out.art.RunningArtRedisPort;
import com.serverbe.application.port.out.jpa.RunningArtRepositoryPort;
import com.serverbe.domain.exception.art.ArtErrorCode;
import com.serverbe.domain.exception.art.ArtException;
import com.serverbe.domain.model.art.RunningArt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link RunningArtSearchService}는 {@link com.serverbe.application.port.in.art.GetNearbyRunningArtUseCase}만
 * 구현합니다. Redis GEO를 1차 필터로 쓰고 DB에서 상세를 채우는 두 단계가 이 클래스의 계약입니다.
 */
@ExtendWith(MockitoExtension.class)
class RunningArtSearchServiceTest {

    private RunningArtSearchService runningArtSearchService;

    @Mock
    private RunningArtRepositoryPort repositoryPort;
    @Mock
    private RunningArtRedisPort runningArtRedisPort;

    @BeforeEach
    void setUp() {
        // ArtSearchPolicy는 순수 record라 mocking하지 않고, application.yml의 실제 운영값
        // (art.max-radius, art.max-result-limit)과 동일한 값으로 실제 인스턴스를 만들어 주입합니다.
        // (생성자가 값을 즉시 읽으므로 @InjectMocks로는 NPE가 납니다.)
        ArtSearchPolicy artSearchPolicy = new ArtSearchPolicy(5000.0, 5);
        runningArtSearchService = new RunningArtSearchService(repositoryPort, runningArtRedisPort, artSearchPolicy);
    }

    @Test
    @DisplayName("성공: 주변 런닝 아트 검색 시, Redis 조회 후 DB 조회가 이어서 실행된다.")
    void getNearbyArts_Success() {
        // given
        List<Long> mockRedisIds = List.of(1L, 2L);
        List<RunningArt> mockDbResults = List.of(
                RunningArt.builder().id(1L).title("아트1").build(),
                RunningArt.builder().id(2L).title("아트2").build()
        );

        // Redis에서는 반경 내의 ID(1, 2)를 스트리밍
        given(runningArtRedisPort.findNearbyIds(37.0, 127.0, 5.0))
                .willReturn(Flux.fromIterable(mockRedisIds));

        // DB에서는 ID 리스트를 받아 상세 정보를 반환
        given(repositoryPort.findAllByIdIn(mockRedisIds))
                .willReturn(mockDbResults);

        // when
        Flux<RunningArtResult> resultFlux = runningArtSearchService.getNearbyArts(37.0, 127.0, 5.0);

        // then
        StepVerifier.create(resultFlux)
                .expectNextMatches(result -> result.id().equals(1L))
                .expectNextMatches(result -> result.id().equals(2L))
                .verifyComplete();

        verify(repositoryPort, times(1)).findAllByIdIn(mockRedisIds);
    }

    @Test
    @DisplayName("실패: 요청 반경이 최대 허용치를 초과하면 INVALID_RADIUS 예외가 발생하고 Redis 조회는 시도되지 않는다")
    void getNearbyArts_Fail_RadiusExceedsMax() {
        // when & then: setUp에서 maxRadius=5000.0으로 설정했으므로 6000.0은 초과값
        StepVerifier.create(runningArtSearchService.getNearbyArts(37.0, 127.0, 6000.0))
                .expectErrorMatches(e -> e instanceof ArtException ae && ae.getErrorCode() == ArtErrorCode.INVALID_RADIUS)
                .verify();

        verify(runningArtRedisPort, never()).findNearbyIds(any(), any(), any());
    }
}
