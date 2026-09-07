package com.serverbe.application.port.dto;

import java.util.List;
import java.util.function.Function;

/**
 * @param content       현재 페이지의 항목들
 * @param page          0부터 시작하는 페이지 번호
 * @param size          요청한 페이지 크기
 * @param totalElements 조건에 해당하는 전체 항목 수
 * @param <T>           항목 타입
 * @responsibility 페이징 조회 결과를 프레임워크와 무관한 형태로 표현합니다.
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    public PageResult {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /**
     * @param mapper 항목 변환 함수
     * @return 항목만 변환되고 페이지 메타데이터는 그대로인 결과
     * @responsibility 도메인 모델 페이지를 DTO 페이지로 옮깁니다.
     */
    public <R> PageResult<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = content.stream().<R>map(mapper).toList();
        return new PageResult<>(mapped, page, size, totalElements);
    }

    /**
     * @return 전체 페이지 수. 항목이 없으면 0이다.
     * @implNote {@code size}가 0이면 나눗셈이 불가능하므로 1로 간주합니다. 실제로는 웹 계층이
     * {@code size} 0을 넘기지 않지만, 계산식이 예외를 던지는 것보다는 나은 값을 돌려주는 편이 낫습니다.
     */
    public int totalPages() {
        if (size <= 0) {
            return totalElements == 0 ? 0 : 1;
        }
        return (int) Math.ceil((double) totalElements / (double) size);
    }
}
