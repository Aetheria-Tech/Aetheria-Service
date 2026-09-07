package com.serverbe.application.port.in.dto.art;

/**
 * @param id       런닝 아트 식별자
 * @param startLat 시작 지점 위도
 * @param startLon 시작 지점 경도
 * @responsibility Redis GEO 인덱스 적재에 필요한 최소 정보만 담습니다.
 * @implNote 이전에는 Querydsl의 {@code @QueryProjection}이 붙어 있었습니다. 애플리케이션 포트의
 * DTO가 특정 조회 기술의 애노테이션을 다는 셈이라, 조회 방식을 바꾸면 포트까지 흔들립니다.
 * 지금은 영속성 어댑터가 {@code Projections.constructor(...)}로 이 레코드를 채웁니다.
 */
public record RunningArtLocationDto(
        Long id,
        Double startLat,
        Double startLon
) {
}
