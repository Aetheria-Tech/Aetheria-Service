package com.serverbe.adapter.in.web.error;

import com.serverbe.domain.exception.ErrorKind;
import com.serverbe.domain.exception.server.ServerErrorCode;
import com.serverbe.domain.exception.server.ServerException;
import org.springframework.http.HttpStatus;

/**
 * @responsibility 도메인의 {@link ErrorKind}를 HTTP 상태 코드로 번역합니다.
 * @implSpec HTTP는 웹 어댑터의 관심사이므로 이 매핑은 도메인이 아니라 여기에만 존재합니다.
 * 애플리케이션 전체에서 {@link ErrorKind}가 HTTP를 만나는 지점은 여기 하나입니다.
 * @implNote {@code default} 절이 없는 switch 식으로 작성했습니다. {@link ErrorKind}에 상수가 추가되면
 * 런타임에 조용히 500으로 떨어지는 대신 <b>컴파일이 깨집니다.</b>
 */
public final class ErrorKindHttpStatusMapper {

    private ErrorKindHttpStatusMapper() {
        throw new ServerException(ServerErrorCode.UTILITY_CLASS);
    }

    /**
     * @param kind 번역할 도메인 에러 종류
     * @return 대응하는 HTTP 상태 코드
     */
    public static HttpStatus toHttpStatus(ErrorKind kind) {
        return switch (kind) {
            case INVALID_INPUT    -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED  -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN        -> HttpStatus.FORBIDDEN;
            case NOT_FOUND        -> HttpStatus.NOT_FOUND;
            case CONFLICT         -> HttpStatus.CONFLICT;
            case RATE_LIMITED     -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL_ERROR   -> HttpStatus.INTERNAL_SERVER_ERROR;
            case UPSTREAM_FAILURE -> HttpStatus.BAD_GATEWAY;
        };
    }
}
