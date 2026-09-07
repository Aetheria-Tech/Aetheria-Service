package com.serverbe.application.service;

import com.serverbe.application.port.in.art.WarmUpGeoIndexUseCase;
import com.serverbe.application.port.in.dto.art.RunningArtLocationDto;
import com.serverbe.application.port.out.art.RunningArtRedisPort;
import com.serverbe.application.port.out.jpa.RunningArtRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 저장소의 공간(Spatial) 데이터를 Redis GEO 인덱스로 동기화하는 캐시 웜업 서비스.
 * <p>
 * <b>도입 배경:</b><br>
 * 애플리케이션 시작 직후 첫 사용자가 위치 기반 검색을 요청할 때 발생할 수 있는
 * 지연(Cold Start) 현상을 방지하기 위해, 서버가 트래픽을 받기 직전에
 * RDB의 모든 런닝 아트 위치 데이터를 Redis 메모리에 미리 적재합니다.
 * </p>
 *
 * @implNote 언제 실행할지는 이 서비스가 정하지 않습니다. 기동 시점 트리거는 인바운드 어댑터인
 * {@code adapter.in.event.RedisGeoWarmUpListener}가 겁니다. 예전에는 스프링 이벤트 애노테이션이
 * 애플리케이션 서비스에 직접 붙어 있어, 애플리케이션이 자기 실행 시점을 알고 있었습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeoIndexWarmUpService implements WarmUpGeoIndexUseCase {

    private final RunningArtRepositoryPort repositoryPort;
    private final RunningArtRedisPort redisPort;

    /**
     * <b>동기화 파이프라인 흐름:</b><br>
     * 1. <b>초기화 (Blocking):</b> DB에서 삭제되었지만 Redis에 남아있는 '유령 데이터(Stale Data)'를 방지하기 위해 기존 GEO Key를 완전히 삭제합니다.<br>
     * 2. <b>고속 로딩:</b> MySQL에서 무거운 전체 엔티티가 아닌, 식별자와 위경도만 포함된 {@link RunningArtLocationDto} 형태로 가볍게 조회합니다.<br>
     * 3. <b>비동기 적재 (Reactive Stream):</b> 조회된 수많은 데이터를 {@link Flux} 기반의 비동기 스트림으로 전환하여 Redis에 병렬로 고속 삽입합니다. 일부 적재 실패가 발생하더라도 전체 프로세스가 중단되지 않도록 예외를 무시({@code onErrorContinue})하고 남은 작업을 진행합니다.
     */
    @Override
    public void warmUpGeoIndex() {
        log.info("[Redis Warm-up] MySQL -> Redis GEO 데이터 동기화를 시작합니다.");

        // 1. 기존 Redis 데이터 초기화 (유령 데이터 방지)
        // 서버 기동 시점이므로 안전하게 block()을 사용하여 초기화가 보장된 후 다음 단계로 넘어갑니다.
        redisPort.clearAllLocations().block();
        log.info("기존 Redis GEO 데이터 초기화 완료");

        // 2. MySQL에서 위치 데이터만 고속 로딩
        List<RunningArtLocationDto> locations = repositoryPort.findAllLocations();

        if (locations.isEmpty()) {
            log.info("동기화할 런닝 아트 데이터가 없습니다.");
            return;
        }

        // 3. Redis에 일괄 삽입 (비동기 스트림 처리)
        log.info("총 {}건의 데이터를 Redis에 적재합니다...", locations.size());

        Flux.fromIterable(locations)
                .flatMap(dto -> redisPort.saveLocation(dto.id(), dto.startLat(), dto.startLon()))
                .doOnError(e -> log.error("Redis 적재 중 오류 발생: {}", e.getMessage()))
                // 특정 데이터 삽입 실패 시 스트림이 멈추지 않고 계속 진행되도록 처리
                .onErrorContinue((e, obj) -> {})
                .count()
                .subscribe(
                        count -> log.info("[Redis Warm-up] 완료! 총 {}건 동기화 성공", count),
                        error -> log.error("[Redis Warm-up] 치명적 오류로 동기화 실패: {}", error.getMessage())
                );
    }
}
