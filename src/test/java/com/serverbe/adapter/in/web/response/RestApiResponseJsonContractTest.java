package com.serverbe.adapter.in.web.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.server.ServerErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @responsibility 클라이언트가 의존하는 에러 응답 JSON이 리팩터링 전후로 동일한지 고정합니다.
 * @implNote 기대 문자열은 {@code docs/troubleshooting/11-design-notes.md}에 기록된 실제 응답입니다.
 * {@code httpStatus}가 상태 코드 숫자가 아니라 <b>enum 이름</b>으로 나간다는 점이 요점이라,
 * {@code RestApiResponse}의 레코드 컴포넌트 타입을 {@code HttpStatus}에서 바꾸면 이 테스트가 깨집니다.
 */
@DisplayName("표준 응답 JSON 계약")
class RestApiResponseJsonContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("실패 응답은 success·httpStatus·error 형태를 유지한다")
    void 실패_응답은_success_httpStatus_error_형태를_유지한다() throws Exception {
        String json = objectMapper.writeValueAsString(
                RestApiResponse.fail(AuthErrorCode.JWT_TOKEN_IS_EMPTY));

        assertThat(json).isEqualTo(
                "{\"success\":false,\"httpStatus\":\"BAD_REQUEST\","
                        + "\"error\":{\"code\":\"JWT_002\",\"message\":\"JWT 토큰 값이 비어있습니다.\"}}");
    }

    @Test
    @DisplayName("상세 사유가 있는 실패 응답은 message만 대체된다")
    void 상세_사유가_있는_실패_응답은_message만_대체된다() throws Exception {
        String json = objectMapper.writeValueAsString(
                RestApiResponse.fail(ServerErrorCode.INVALID_REQUEST_PARAMETER, "[radius]: 5를 넘을 수 없습니다"));

        assertThat(json).isEqualTo(
                "{\"success\":false,\"httpStatus\":\"BAD_REQUEST\","
                        + "\"error\":{\"code\":\"COMMON_001\",\"message\":\"[radius]: 5를 넘을 수 없습니다\"}}");
    }

    @Test
    @DisplayName("에러 종류마다 httpStatus 이름이 달라진다")
    void 에러_종류마다_httpStatus_이름이_달라진다() throws Exception {
        assertThat(objectMapper.writeValueAsString(RestApiResponse.fail(AuthErrorCode.UNAUTHORIZED)))
                .contains("\"httpStatus\":\"UNAUTHORIZED\"");
        assertThat(objectMapper.writeValueAsString(RestApiResponse.fail(ServerErrorCode.RESOURCE_NOT_FOUND)))
                .contains("\"httpStatus\":\"NOT_FOUND\"");
        assertThat(objectMapper.writeValueAsString(RestApiResponse.fail(ServerErrorCode.ASYNC_RACE_CONDITION)))
                .contains("\"httpStatus\":\"CONFLICT\"");
    }

    @Test
    @DisplayName("성공 응답에는 error 필드가 실리지 않는다")
    void 성공_응답에는_error_필드가_실리지_않는다() throws Exception {
        String json = objectMapper.writeValueAsString(RestApiResponse.success("payload"));

        assertThat(json).isEqualTo("{\"success\":true,\"httpStatus\":\"OK\",\"data\":\"payload\"}");
    }
}
