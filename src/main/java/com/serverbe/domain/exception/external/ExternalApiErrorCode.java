package com.serverbe.domain.exception.external;

import com.serverbe.domain.exception.ErrorCode;
import com.serverbe.domain.exception.ErrorKind;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ExternalApiErrorCode implements ErrorCode {
    // OAUTH
    NOT_FOUND_SOCIAL_TOKEN(ErrorKind.NOT_FOUND, "AUTH_001", "소셜 토큰을 찾을 수 없습니다."),
    FAILED_SOCIAL_API(ErrorKind.UPSTREAM_FAILURE, "AUTH_002", "외부 소셜 API 호출에 실패했습니다."),

    // EXTERNAL API
    EXTERNAL_API_SERVER_ERROR(ErrorKind.UPSTREAM_FAILURE, "EXTERNAL_001", "외부 API 응답이 잘못되었습니다."),

    // KAKAO GEOCODING
    FAILED_GEOCODING_API(ErrorKind.UPSTREAM_FAILURE, "GEOCODE_001", "지오코딩 API 호출에 실패했습니다."),
    INVALID_ADDRESS(ErrorKind.INVALID_INPUT, "GEOCODE_002", "주소가 올바르지 않습니다"),

    // GOOGLE OAUTH
    INVALID_REFRESH_TOKEN(ErrorKind.INVALID_INPUT, "EXTERNAL_101", "리프레시 토큰이 없어서 연동 해제가 불가능합니다."),


    ;


    private final ErrorKind kind;
    private final String code;
    private final String message;

}