package com.serverbe.application.service;

import com.serverbe.application.config.ArtSearchPolicy;
import com.serverbe.application.port.in.art.GetNearbyRunningArtUseCase;
import com.serverbe.application.port.in.dto.art.RunningArtResult;
import com.serverbe.application.port.out.art.RunningArtRedisPort;
import com.serverbe.application.port.out.jpa.RunningArtRepositoryPort;
import com.serverbe.domain.exception.art.ArtErrorCode;
import com.serverbe.domain.exception.art.ArtException;
import com.serverbe.domain.model.art.RunningArt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @responsibility 위치를 기준으로 주변 런닝 아트를 탐색합니다.
 * @implSpec {@link RunningArtService}에서 갈라져 나왔습니다. 소유권 검증을 공유하는 CRUD와 달리
 * 이쪽은 Redis GEO 인덱스를 1차 필터로 쓰는 <b>리액티브 탐색</b>이고, 소유권 개념 자체가 없습니다
 * (주변 아트는 남의 것도 보입니다). 협력자도 다릅니다 — {@link ArtSearchPolicy}를 쓰는 것은
 * 이 클래스뿐입니다.
 * @implNote <b>이 클래스에는 {@code @Transactional}을 붙이지 않습니다.</b> 스레드에 바인딩되는
 * 선언적 트랜잭션은 리액티브 파이프라인 위에서 아무 일도 하지 않으며,
 * {@code LayerDependencyTest}의 {@code 트랜잭션_메서드는_리액티브_타입을_반환하지_않는다}가
 * 클래스 레벨 애노테이션까지 검사해 이를 강제합니다.
 */
@Slf4j
@Service
public class RunningArtSearchService implements GetNearbyRunningArtUseCase {

    private final RunningArtRepositoryPort repositoryPort;
    private final RunningArtRedisPort runningArtRedisPort;
    private final double maxRadius;
    private final int maxResultLimit;

    public RunningArtSearchService(RunningArtRepositoryPort repositoryPort, RunningArtRedisPort runningArtRedisPort, ArtSearchPolicy artSearchPolicy) {
        this.repositoryPort = repositoryPort;
        this.runningArtRedisPort = runningArtRedisPort;
        this.maxRadius = artSearchPolicy.maxRadius();
        this.maxResultLimit = artSearchPolicy.maxResultLimit();
    }

    /**
     * @param lat    검색 중심점의 위도 (Latitude)
     * @param lon    검색 중심점의 경도 (Longitude)
     * @param radius 검색 반경 (단위: km)
     * @return 검색된 주변 런닝 아트 목록을 스트리밍하는 {@link Flux<RunningArtResult>}
     * @requirement UC-ART-07: 위치 기반 주변 런닝 아트 탐색 요청
     * @responsibility 주어진 좌표와 반경을 기준으로 주변에 위치한 런닝 아트를 탐색하여 반환합니다. 대용량 데이터 환경에서의 RDBMS 부하를 줄이기 위해 Redis 기반의 1차 필터링을 수행합니다.
     * @implSpec 1. {@link RunningArtRedisPort#findNearbyIds(Double, Double, Double)}를 통해 Redis GEO 공간 인덱스에서 반경 내 데이터의 고유 ID 목록을 빠르게 조회합니다.<br>
     * 2. 조회된 ID 목록이 존재할 경우, 블로킹 환경을 위한 {@link Schedulers#boundedElastic()} 스레드에서 {@link RunningArtRepositoryPort#findAllByIdIn(List)}를 호출하여 DB에서 상세 정보를 일괄(Batch) 페치합니다.
     * @implNote Redis의 {@code GEOSEARCH}는 거리순 정렬을 제공하지만 RDBMS의 {@code IN} 쿼리는 식별자 순서를 보장하지 않으므로,
     * <b>DB 결과를 {@code Map}에 담아 두고 Redis가 준 {@code ids} 순서로 다시 늘어놓아 거리순을 복원합니다.</b>
     * 순서를 만드는 것은 DB가 아니라 {@code ids}이며, 그 사이 삭제되어 DB에 없는 id는 걸러집니다.
     */
    @Override
    public Flux<RunningArtResult> getNearbyArts(Double lat, Double lon, Double radius) {
        // 1. 반경 최대값 검증 (방어적 프로그래밍)
        if (radius > maxRadius) {
            return Flux.error(new ArtException(ArtErrorCode.INVALID_RADIUS));
        }

        return runningArtRedisPort.findNearbyIds(lat, lon, radius)
                .take(maxResultLimit)
                .collectList()
                .filter(ids -> !ids.isEmpty())
                .flatMapMany(ids ->
                        Mono.fromCallable(() -> {
                                    List<RunningArt> arts = repositoryPort.findAllByIdIn(ids);

                                    Map<Long, RunningArt> artMap = arts.stream()
                                            .collect(Collectors.toMap(
                                                    RunningArt::id,
                                                    Function.identity(),
                                                    (existing, replacement) -> existing
                                            ));

                                    return ids.stream()
                                            .map(artMap::get)
                                            .filter(Objects::nonNull)
                                            .map(RunningArtResult::toResult)
                                            .toList();
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapIterable(Function.identity())
                );
    }
}
