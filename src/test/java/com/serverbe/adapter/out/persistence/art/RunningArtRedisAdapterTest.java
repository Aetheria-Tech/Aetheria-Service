package com.serverbe.adapter.out.persistence.art;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.ReactiveGeoOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * @responsibility Redis GEO 명령에 넘기는 <b>좌표 순서</b>와 검색 인자를 고정합니다.
 * @implSpec 가장 중요한 단언은 좌표 순서입니다. Redis GEO는 <b>경도-위도</b> 순으로 받는데, 우리가
 * 쓰는 도메인 언어는 늘 <b>위도-경도</b>입니다. 누가 "위도가 먼저가 자연스럽다"며 뒤집으면
 * <b>컴파일도 테스트도 통과하면서 모든 위치가 조용히 틀립니다</b> — 서울에서 찾으면 남극이 나오는
 * 종류의 실패이고, 예외가 나지 않아 로그에도 안 남습니다.
 * @implNote {@code geoKey}가 {@code @Value} <b>필드 주입</b>이라 생성자로 넣을 수 없어
 * {@code ReflectionTestUtils}로 채웁니다. 이 어색함 자체가 필드 주입의 비용입니다 — 생성자
 * 주입이었다면 테스트가 값을 그냥 넘겼을 것입니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("런닝 아트 GEO 인덱스 어댑터")
class RunningArtRedisAdapterTest {

    @Mock
    private ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    @Mock
    private ReactiveGeoOperations<String, String> geoOperations;

    @InjectMocks
    private RunningArtRedisAdapter adapter;

    private static final String GEO_KEY = "running_art:locations";
    private static final Long ART_ID = 7L;
    private static final double LAT = 37.5665;
    private static final double LON = 126.9780;

    @BeforeEach
    void injectGeoKey() {
        ReflectionTestUtils.setField(adapter, "geoKey", GEO_KEY);
    }

    @Test
    @DisplayName("저장은 경도-위도 순으로 좌표를 넘긴다")
    void 저장은_경도_위도_순으로_넘긴다() {
        given(reactiveRedisTemplate.opsForGeo()).willReturn(geoOperations);
        given(geoOperations.add(eq(GEO_KEY), any(Point.class), anyString())).willReturn(Mono.just(1L));

        StepVerifier.create(adapter.saveLocation(ART_ID, LAT, LON))
                .expectNext(1L)
                .verifyComplete();

        ArgumentCaptor<Point> point = ArgumentCaptor.forClass(Point.class);
        verify(geoOperations).add(eq(GEO_KEY), point.capture(), eq("7"));

        assertThat(point.getValue().getX()).isEqualTo(LON, within(1e-9));
        assertThat(point.getValue().getY()).isEqualTo(LAT, within(1e-9));
    }

    @Test
    @DisplayName("주변 검색도 경도-위도 순이며 반경은 킬로미터로 나간다")
    void 주변_검색도_경도_위도_순이다() {
        given(reactiveRedisTemplate.opsForGeo()).willReturn(geoOperations);
        given(geoOperations.radius(eq(GEO_KEY), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .willReturn(Flux.empty());

        StepVerifier.create(adapter.findNearbyIds(LAT, LON, 3.0)).verifyComplete();

        ArgumentCaptor<Circle> circle = ArgumentCaptor.forClass(Circle.class);
        verify(geoOperations).radius(eq(GEO_KEY), circle.capture(), any(RedisGeoCommands.GeoRadiusCommandArgs.class));

        assertThat(circle.getValue().getCenter().getX()).isEqualTo(LON, within(1e-9));
        assertThat(circle.getValue().getCenter().getY()).isEqualTo(LAT, within(1e-9));
        assertThat(circle.getValue().getRadius().getValue()).isEqualTo(3.0);
        assertThat(circle.getValue().getRadius().getMetric()).isEqualTo(Metrics.KILOMETERS);
    }

    /**
     * @implNote 정렬을 빼면 결과가 <b>거리순이 아닌 임의 순서</b>로 돌아옵니다. 목록 상단이
     * "가장 가까운 곳"이라는 화면의 전제가 깨지는데, 개수는 맞으므로 눈에 띄지 않습니다.
     */
    @Test
    @DisplayName("주변 검색은 거리 오름차순을 요청한다")
    void 주변_검색은_거리_오름차순을_요청한다() {
        given(reactiveRedisTemplate.opsForGeo()).willReturn(geoOperations);
        given(geoOperations.radius(eq(GEO_KEY), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .willReturn(Flux.empty());

        adapter.findNearbyIds(LAT, LON, 3.0).blockLast();

        ArgumentCaptor<RedisGeoCommands.GeoRadiusCommandArgs> args =
                ArgumentCaptor.forClass(RedisGeoCommands.GeoRadiusCommandArgs.class);
        verify(geoOperations).radius(eq(GEO_KEY), any(Circle.class), args.capture());

        assertThat(args.getValue().getSortDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("검색 결과의 멤버 이름은 아트 ID로 파싱된다")
    void 검색_결과는_아트_ID로_파싱된다() {
        given(reactiveRedisTemplate.opsForGeo()).willReturn(geoOperations);
        given(geoOperations.radius(eq(GEO_KEY), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .willReturn(Flux.just(geoResult("7"), geoResult("9")));

        StepVerifier.create(adapter.findNearbyIds(LAT, LON, 3.0))
                .expectNext(7L, 9L)
                .verifyComplete();
    }

    @Test
    @DisplayName("삭제는 아트 ID를 멤버 이름으로 넘긴다")
    void 삭제는_아트_ID를_멤버로_넘긴다() {
        given(reactiveRedisTemplate.opsForGeo()).willReturn(geoOperations);
        given(geoOperations.remove(GEO_KEY, "7")).willReturn(Mono.just(1L));

        StepVerifier.create(adapter.removeLocation(ART_ID))
                .expectNext(1L)
                .verifyComplete();
    }

    /**
     * @implNote 세 경우가 갈립니다 — 지운 것이 있으면 {@code true}, 0건이면 {@code false},
     * 그리고 <b>키가 없어 빈 {@code Mono}가 오면</b> {@code defaultIfEmpty}가 {@code false}로
     * 막습니다. 마지막 것이 없으면 웜업 경로가 빈 신호를 받고 그대로 멈춥니다.
     */
    @Test
    @DisplayName("전체 삭제는 지운 건수가 있을 때만 true다")
    void 전체_삭제는_지운_건수가_있을_때만_true다() {
        given(reactiveRedisTemplate.delete(GEO_KEY))
                .willReturn(Mono.just(1L), Mono.just(0L), Mono.empty());

        StepVerifier.create(adapter.clearAllLocations()).expectNext(true).verifyComplete();
        StepVerifier.create(adapter.clearAllLocations()).expectNext(false).verifyComplete();
        StepVerifier.create(adapter.clearAllLocations()).expectNext(false).verifyComplete();
    }

    private static GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult(String member) {
        return new GeoResult<>(
                new RedisGeoCommands.GeoLocation<>(member, new Point(LON, LAT)),
                new Distance(0.5, Metrics.KILOMETERS));
    }
}
