package com.serverbe.domain.exception;

/**
 * @responsibility 도메인 에러의 식별자·설명·<b>종류</b>를 정의하는 계약입니다.
 * @implSpec 구현체는 {@code private final ErrorKind kind;} 필드에 Lombok {@code @Getter}를 붙여
 * {@link #getKind()}를 만족시킵니다.
 * @implNote 반환 타입이 {@link ErrorKind}이지 HTTP 상태가 아닌 이유는 {@link ErrorKind} 문서를 참고하세요.
 */
public interface ErrorCode {
    ErrorKind getKind();
    String getCode();
    String getMessage();
}
