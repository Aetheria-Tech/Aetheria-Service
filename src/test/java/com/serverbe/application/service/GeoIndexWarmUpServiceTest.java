package com.serverbe.application.service;

import com.serverbe.application.port.in.dto.art.RunningArtLocationDto;
import com.serverbe.application.port.out.art.RunningArtRedisPort;
import com.serverbe.application.port.out.jpa.RunningArtRepositoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * @implNote 웜업이 잘못되면 조용히 틀립니다. 초기화를 건너뛰면 삭제된 런닝 아트가 검색 결과에
 * 계속 뜨고, 중간에 하나 실패했다고 멈추면 그 뒤 데이터가 통째로 인덱스에서 빠집니다.
 * 둘 다 에러 로그 한 줄로 지나갑니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GEO 인덱스 웜업")
class GeoIndexWarmUpServiceTest {

    @Mock
    private RunningArtRepositoryPort repositoryPort;
    @Mock
    private RunningArtRedisPort redisPort;

    @InjectMocks
    private GeoIndexWarmUpService service;

    @Test
    @DisplayName("적재할 위치가 없으면 인덱스만 비우고 끝낸다")
    void 적재할_위치가_없으면_인덱스만_비운다() {
        given(redisPort.clearAllLocations()).willReturn(Mono.just(true));
        given(repositoryPort.findAllLocations()).willReturn(List.of());

        service.warmUpGeoIndex();

        verify(redisPort).clearAllLocations();
        verify(redisPort, never()).saveLocation(anyLong(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("기존 인덱스를 먼저 비운 뒤에 적재한다")
    void 기존_인덱스를_먼저_비운_뒤_적재한다() {
        // 순서가 뒤바뀌면 방금 적재한 데이터를 스스로 지워 인덱스가 비어 버립니다.
        given(redisPort.clearAllLocations()).willReturn(Mono.just(true));
        given(repositoryPort.findAllLocations())
                .willReturn(List.of(new RunningArtLocationDto(1L, 37.5, 127.0)));
        given(redisPort.saveLocation(eq(1L), eq(37.5), eq(127.0))).willReturn(Mono.just(1L));

        service.warmUpGeoIndex();

        InOrder order = inOrder(redisPort);
        order.verify(redisPort).clearAllLocations();
        order.verify(redisPort).saveLocation(1L, 37.5, 127.0);
    }

    @Test
    @DisplayName("일부 적재가 실패해도 나머지를 계속 적재한다")
    void 일부_적재가_실패해도_나머지를_계속_적재한다() {
        given(redisPort.clearAllLocations()).willReturn(Mono.just(true));
        given(repositoryPort.findAllLocations()).willReturn(List.of(
                new RunningArtLocationDto(1L, 37.5, 127.0),
                new RunningArtLocationDto(2L, 37.6, 127.1),
                new RunningArtLocationDto(3L, 37.7, 127.2)));
        given(redisPort.saveLocation(eq(1L), anyDouble(), anyDouble())).willReturn(Mono.just(1L));
        given(redisPort.saveLocation(eq(2L), anyDouble(), anyDouble()))
                .willReturn(Mono.error(new IllegalStateException("Redis 일시 오류")));
        given(redisPort.saveLocation(eq(3L), anyDouble(), anyDouble())).willReturn(Mono.just(1L));

        service.warmUpGeoIndex();

        verify(redisPort, times(3)).saveLocation(anyLong(), anyDouble(), anyDouble());
    }
}
