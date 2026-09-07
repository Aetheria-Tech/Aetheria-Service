package com.serverbe.adapter.in.web.error;

import com.serverbe.domain.exception.ErrorKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorKind -> HttpStatus 매핑")
class ErrorKindHttpStatusMapperTest {

    @ParameterizedTest
    @EnumSource(ErrorKind.class)
    @DisplayName("모든 ErrorKind는 HTTP 상태로 매핑된다")
    void 모든_ErrorKind는_HTTP_상태로_매핑된다(ErrorKind kind) {
        assertThat(ErrorKindHttpStatusMapper.toHttpStatus(kind)).isNotNull();
    }

    /**
     * 리팩터링 이전 각 에러 코드가 직접 들고 있던 상태값을 그대로 재현합니다.
     * 이 표가 바뀌면 클라이언트의 에러 처리가 깨집니다.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "INVALID_INPUT,    400",
            "UNAUTHENTICATED,  401",
            "FORBIDDEN,        403",
            "NOT_FOUND,        404",
            "CONFLICT,         409",
            "RATE_LIMITED,     429",
            "INTERNAL_ERROR,   500",
            "UPSTREAM_FAILURE, 502",
    })
    @DisplayName("기존 응답 계약과 동일한 상태 코드를 유지한다")
    void 기존_응답_계약과_동일한_상태_코드를_유지한다(ErrorKind kind, int expectedStatus) {
        assertThat(ErrorKindHttpStatusMapper.toHttpStatus(kind).value()).isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("서로 다른 ErrorKind는 서로 다른 상태 코드로 간다")
    void 서로_다른_ErrorKind는_서로_다른_상태_코드로_간다() {
        assertThat(ErrorKind.values())
                .extracting(ErrorKindHttpStatusMapper::toHttpStatus)
                .doesNotHaveDuplicates();
    }
}
