package com.serverbe.adapter.in.web.support;

import com.serverbe.application.port.dto.PageQuery;
import com.serverbe.application.port.dto.PageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @implNote 정렬이 조용히 사라지는 사고를 막는 것이 이 테스트의 목적입니다. {@code ?sort=id,desc}가
 * 무시되어도 응답은 200이고 목록도 나오므로, 순서가 뒤집힌 것을 눈으로 알아채기 어렵습니다.
 */
@DisplayName("페이징 타입 변환")
class PageQueryMapperTest {

    @Test
    @DisplayName("페이지 번호와 크기를 그대로 옮긴다")
    void 페이지_번호와_크기를_그대로_옮긴다() {
        PageQuery query = PageQueryMapper.toPageQuery(PageRequest.of(3, 25));

        assertThat(query.page()).isEqualTo(3);
        assertThat(query.size()).isEqualTo(25);
        assertThat(query.sorts()).isEmpty();
    }

    @Test
    @DisplayName("정렬 조건이 여러 개여도 순서와 방향이 보존된다")
    void 정렬_조건이_여러개여도_순서와_방향이_보존된다() {
        Pageable pageable = PageRequest.of(2, 15,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id")));

        PageQuery query = PageQueryMapper.toPageQuery(pageable);

        assertThat(query.sorts()).containsExactly(
                new PageQuery.SortOrder("createdAt", PageQuery.Direction.DESC),
                new PageQuery.SortOrder("id", PageQuery.Direction.ASC));
    }

    @Test
    @DisplayName("PageResult를 다시 Page로 감싸면 메타데이터가 그대로 실린다")
    void PageResult를_다시_Page로_감싸면_메타데이터가_그대로_실린다() {
        Pageable pageable = PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "id"));
        PageResult<String> result = new PageResult<>(List.of("a", "b"), 1, 20, 42);

        Page<String> page = PageQueryMapper.toPage(result, pageable);

        assertThat(page.getContent()).containsExactly("a", "b");
        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(20);
        assertThat(page.getTotalElements()).isEqualTo(42);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "id"));
    }
}
