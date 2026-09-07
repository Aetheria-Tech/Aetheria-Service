package com.serverbe.application.port.dto;

import java.util.List;

/**
 * @param page  0부터 시작하는 페이지 번호
 * @param size  한 페이지의 크기
 * @param sorts 정렬 조건. 비어 있으면 저장소 기본 순서를 따른다.
 * @responsibility 페이징·정렬 요청을 프레임워크와 무관한 형태로 표현합니다.
 * @implSpec {@code sorts}는 선택 항목처럼 보이지만 생략해서는 안 됩니다. 현재 목록 API가
 * {@code ?sort=createdAt,desc}를 받고 있어, 이 필드가 없으면 정렬이 <b>에러 없이</b> 사라집니다.
 * @implNote Spring Data의 {@code Pageable}을 그대로 쓰지 않는 이유는 포트 시그니처가
 * spring-data에 묶이면 애플리케이션 계층이 저장소 기술을 알게 되기 때문입니다.
 * {@code Pageable} 변환은 영속성 어댑터와 웹 어댑터 안에서만 일어납니다.
 */
public record PageQuery(int page, int size, List<SortOrder> sorts) {

    public PageQuery {
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
    }

    /**
     * @return 정렬 조건이 없는 페이지 요청
     */
    public static PageQuery of(int page, int size) {
        return new PageQuery(page, size, List.of());
    }

    /**
     * @param property  정렬 기준 필드 이름
     * @param direction 정렬 방향
     */
    public record SortOrder(String property, Direction direction) {
    }

    public enum Direction {
        ASC, DESC
    }
}
