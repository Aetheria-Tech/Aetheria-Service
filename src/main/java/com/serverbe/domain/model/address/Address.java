package com.serverbe.domain.model.address;

import com.serverbe.domain.exception.external.ExternalApiErrorCode;
import com.serverbe.domain.exception.external.ExternalApiException;

import java.util.regex.Pattern;

/**
 * @author Duskafka
 * @responsibility 런닝 아트가 생성되는 위치의 주소 정보를 관리하며, 생성 시 스스로의 유효성을 검증합니다.
 * @implNote 단순 String이 아닌 도메인 모델로서 주소의 비즈니스 규칙을 강제합니다.
 */
public record Address(String value) {

    private static final Pattern ADDRESS_PATTERN = Pattern.compile("^[a-zA-Z0-9가-힣\\s\\-\\(\\),.#~]+$");
    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 100;

    public Address {
        validate(value);
    }

    private void validate(String address) {
        if (address == null || address.isBlank()) {
            throw new ExternalApiException(ExternalApiErrorCode.INVALID_ADDRESS, "주소를 입력해주세요.");
        }

        String trimmed = address.trim();

        if (trimmed.length() < MIN_LENGTH) {
            throw new ExternalApiException(ExternalApiErrorCode.INVALID_ADDRESS, "주소가 너무 짧습니다.");
        }

        if (trimmed.length() > MAX_LENGTH) {
            throw new ExternalApiException(ExternalApiErrorCode.INVALID_ADDRESS, "주소의 길이가 너무 깁니다.");
        }

        if (!ADDRESS_PATTERN.matcher(trimmed).matches()) {
            throw new ExternalApiException(ExternalApiErrorCode.INVALID_ADDRESS, "주소에 허용되지 않는 특수문자가 포함되어 있습니다.");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}