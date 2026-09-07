package com.serverbe.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.adapter.in.web.support.PageQueryMapper;
import com.serverbe.adapter.in.web.response.RestApiResponse;
import com.serverbe.application.port.dto.PageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @responsibility 런닝 아트 목록 응답의 JSON 형태가 페이징 리팩터링 전후로 동일한지 고정합니다.
 * @implSpec 기존 응답은 Spring Data {@code PageImpl}의 기본 직렬화 형태(최상위 키 11개)였습니다.
 * 자체 페이지 DTO로 바꾸면 {@code pageable}·{@code sort} 같은 중첩 객체가 사라져 클라이언트가 깨지므로,
 * 컨트롤러가 {@link PageQueryMapper#toPage} 로 다시 감싸 형태를 유지합니다. 이 테스트가 그 약속입니다.
 * @implNote 일부러 {@code @WebFluxTest}를 쓰지 않습니다. 운영은 서블릿 MVC인데 기존
 * {@code RunningArtControllerTest}는 WebFlux 슬라이스라, 거기서 페이징을 검증하면
 * {@code ReactivePageableHandlerMethodArgumentResolver}라는 다른 리졸버를 테스트하게 됩니다.
 */
@DisplayName("런닝 아트 목록 페이징 JSON 계약")
class RunningArtPageJsonContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode serializeListResponse() throws Exception {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));
        PageResult<String> result = new PageResult<>(List.of("첫 번째 런닝 아트"), 0, 20, 1);

        String json = objectMapper.writeValueAsString(
                RestApiResponse.success(PageQueryMapper.toPage(result, pageable)));

        return objectMapper.readTree(json);
    }

    @Test
    @DisplayName("data는 Spring Data Page의 최상위 키 11개를 그대로 유지한다")
    void data는_Page의_최상위_키_11개를_유지한다() throws Exception {
        JsonNode data = serializeListResponse().get("data");

        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "content", "pageable", "totalElements", "totalPages", "last",
                "size", "number", "sort", "numberOfElements", "first", "empty");
    }

    @Test
    @DisplayName("pageable 중첩 객체가 사라지지 않는다")
    void pageable_중첩_객체가_사라지지_않는다() throws Exception {
        JsonNode pageable = serializeListResponse().get("data").get("pageable");

        assertThat(pageable).isNotNull();
        assertThat(pageable.get("pageNumber").asInt()).isZero();
        assertThat(pageable.get("pageSize").asInt()).isEqualTo(20);
        assertThat(pageable.get("sort").get("sorted").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("페이지 메타데이터 값이 요청과 일치한다")
    void 페이지_메타데이터_값이_요청과_일치한다() throws Exception {
        JsonNode data = serializeListResponse().get("data");

        assertThat(data.get("totalElements").asLong()).isEqualTo(1);
        assertThat(data.get("totalPages").asInt()).isEqualTo(1);
        assertThat(data.get("size").asInt()).isEqualTo(20);
        assertThat(data.get("number").asInt()).isZero();
        assertThat(data.get("first").asBoolean()).isTrue();
        assertThat(data.get("last").asBoolean()).isTrue();
        assertThat(data.get("content")).hasSize(1);
    }

    @Test
    @DisplayName("표준 응답 껍데기(success·httpStatus)도 그대로다")
    void 표준_응답_껍데기도_그대로다() throws Exception {
        JsonNode root = serializeListResponse();

        assertThat(root.get("success").asBoolean()).isTrue();
        assertThat(root.get("httpStatus").asText()).isEqualTo("OK");
        assertThat(root.has("error")).isFalse();
    }
}
