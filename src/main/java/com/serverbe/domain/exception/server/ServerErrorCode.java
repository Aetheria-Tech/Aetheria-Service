package com.serverbe.domain.exception.server;

import com.serverbe.domain.exception.ErrorCode;
import com.serverbe.domain.exception.ErrorKind;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ServerErrorCode implements ErrorCode {
    INVALID_REQUEST_PARAMETER(ErrorKind.INVALID_INPUT, "COMMON_001", "잘못된 요청 파라미터입니다."),
    MALFORMED_JSON_REQUEST(ErrorKind.INVALID_INPUT, "COMMON_002", "잘못된 형식의 JSON 요청입니다."),
    INTERNAL_SERVER_ERROR(ErrorKind.INTERNAL_ERROR, "COMMON_003", "서버 내부 오류가 발생했습니다."),
    DE_IDENTIFIED_DEVICES(ErrorKind.INVALID_INPUT, "COMMON_004", "Device ID를 식별할 수 없습니다. X-Device-Id 또는 User-Agent 헤더를 확인해주세요."),
    UTILITY_CLASS(ErrorKind.INTERNAL_ERROR, "COMMON_005", "코드 내부에서 유틸리티 클래스를 생성자로 생성합니다."),
    TOO_MANY_REQUESTS(ErrorKind.RATE_LIMITED, "COMMON_006", "너무 많은 요청이 들어왔습니다. 잠시 후에 요청을 보내주세요."),
    HASHING_ALGORITHM_NOT_FOUND(ErrorKind.INTERNAL_ERROR, "COMMON_007", "해싱 알고리즘을 찾지 못했습니다."),
    RESOURCE_NOT_FOUND(ErrorKind.NOT_FOUND, "COMMON_008", "요청한 리소스를 찾을 수 없습니다."),
    POLYLINE_PARSE_ERROR(ErrorKind.INTERNAL_ERROR, "COMMON_009","Encoded polyline string cannot be null or empty"),
    ASYNC_RACE_CONDITION(ErrorKind.CONFLICT, "COMMON_010", "비동기 처리 중 경합 조건이 발생하여 재시도가 필요합니다."),
    ;

    private final ErrorKind kind;
    private final String code;
    private final String message;
}