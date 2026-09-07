package com.serverbe.application.port.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("페이지 결과")
class PageResultTest {

    @Test
    @DisplayName("전체 페이지 수는 올림으로 계산한다")
    void 전체_페이지_수는_올림으로_계산한다() {
        assertThat(new PageResult<>(List.of(), 0, 10, 11).totalPages()).isEqualTo(2);
        assertThat(new PageResult<>(List.of(), 0, 10, 20).totalPages()).isEqualTo(2);
        assertThat(new PageResult<>(List.of(), 0, 10, 21).totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("항목이 없으면 전체 페이지 수는 0이다")
    void 항목이_없으면_전체_페이지_수는_0이다() {
        assertThat(new PageResult<>(List.of(), 0, 10, 0).totalPages()).isZero();
    }

    @Test
    @DisplayName("페이지 크기가 0이어도 0으로 나누지 않는다")
    void 페이지_크기가_0이어도_0으로_나누지_않는다() {
        assertThat(new PageResult<>(List.of(), 0, 0, 0).totalPages()).isZero();
        assertThat(new PageResult<>(List.of(), 0, 0, 5).totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("map은 항목만 바꾸고 페이지 메타데이터는 보존한다")
    void map은_페이지_메타데이터를_보존한다() {
        PageResult<Integer> source = new PageResult<>(List.of(1, 2, 3), 2, 15, 42);

        PageResult<String> mapped = source.map(String::valueOf);

        assertThat(mapped.content()).containsExactly("1", "2", "3");
        assertThat(mapped.page()).isEqualTo(2);
        assertThat(mapped.size()).isEqualTo(15);
        assertThat(mapped.totalElements()).isEqualTo(42);
    }

    @Test
    @DisplayName("content는 방어적으로 복사되어 외부 변경에 흔들리지 않는다")
    void content는_방어적으로_복사된다() {
        PageResult<Integer> result = new PageResult<>(List.of(1, 2), 0, 10, 2);

        assertThat(result.content()).isUnmodifiable();
    }
}
