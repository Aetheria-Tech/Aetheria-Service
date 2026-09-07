package com.serverbe.domain.model.address;

import com.serverbe.domain.exception.external.ExternalApiErrorCode;
import com.serverbe.domain.exception.external.ExternalApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @responsibility 주소 값 객체의 검증 규칙을 고정합니다.
 * @implNote Spring의 {@code StringUtils.hasText}를 JDK의 {@code isBlank}로 바꾸면서 공백 문자열
 * 처리가 달라지지 않았음을 확인하는 것이 이 테스트의 출발점입니다.
 */
@DisplayName("주소 값 객체")
class AddressTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    @DisplayName("비어 있거나 공백뿐인 주소는 거부한다")
    void 비어_있거나_공백뿐인_주소는_거부한다(String value) {
        assertThatThrownBy(() -> new Address(value))
                .isInstanceOf(ExternalApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ExternalApiErrorCode.INVALID_ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"서", "서울", " 서울 "})
    @DisplayName("공백을 제거한 길이가 3자 미만이면 거부한다")
    void 공백을_제거한_길이가_3자_미만이면_거부한다(String value) {
        assertThatThrownBy(() -> new Address(value)).isInstanceOf(ExternalApiException.class);
    }

    @Test
    @DisplayName("공백을 제거한 길이가 100자를 넘으면 거부한다")
    void 공백을_제거한_길이가_100자를_넘으면_거부한다() {
        assertThatThrownBy(() -> new Address("가".repeat(101)))
                .isInstanceOf(ExternalApiException.class);

        assertThatCode(() -> new Address("가".repeat(100))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"서울@강남", "서울<강남>", "서울;강남"})
    @DisplayName("허용되지 않은 특수문자가 있으면 거부한다")
    void 허용되지_않은_특수문자가_있으면_거부한다(String value) {
        assertThatThrownBy(() -> new Address(value)).isInstanceOf(ExternalApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"서울특별시 강남구 테헤란로 123", "Seoul, Gangnam-gu (Teheran-ro) #123", "경기도 성남시 분당구 정자일로 95~97"})
    @DisplayName("정상 주소는 값을 그대로 보존한다")
    void 정상_주소는_값을_그대로_보존한다(String value) {
        Address address = new Address(value);

        assertThat(address.value()).isEqualTo(value);
        assertThat(address).hasToString(value);
    }
}
