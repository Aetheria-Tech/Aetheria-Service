package com.serverbe.domain.exception;

/**
 * @responsibility 실패의 <b>종류</b>를 도메인 언어로 표현합니다. 도메인은 실패가 어떤 성격인지까지만 알고,
 * 그것이 어떤 HTTP 상태 코드로 나가는지는 알지 않습니다.
 * @implSpec HTTP 매핑은 {@code adapter.in.web.error.ErrorKindHttpStatusMapper} 한 곳에만 존재합니다.
 * @implNote 이전에는 {@link ErrorCode}가 {@code org.springframework.http.HttpStatus}를 직접 반환해
 * 도메인 에러 코드 전체가 Spring Web에 묶여 있었습니다. {@code package-info.java}가 "예외 이름은 HTTP 상태
 * 코드나 기술 스택에 묶이지 않는다"고 적어 둔 규칙을 코드가 어기고 있던 셈입니다.
 */
public enum ErrorKind {

    /** 요청 자체가 규칙에 맞지 않습니다. */
    INVALID_INPUT,

    /** 신원을 증명하지 못했습니다. */
    UNAUTHENTICATED,

    /** 신원은 확인됐지만 이 자원에 대한 권한이 없습니다. */
    FORBIDDEN,

    /** 대상을 찾을 수 없습니다. */
    NOT_FOUND,

    /** 현재 상태와 요청이 충돌합니다. 재시도로 풀릴 수 있습니다. */
    CONFLICT,

    /** 허용된 호출 빈도를 넘었습니다. */
    RATE_LIMITED,

    /** 서버 내부에서 처리에 실패했습니다. */
    INTERNAL_ERROR,

    /** 우리가 의존하는 외부 시스템이 실패했습니다. */
    UPSTREAM_FAILURE
}
