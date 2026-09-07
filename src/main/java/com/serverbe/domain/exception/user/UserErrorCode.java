package com.serverbe.domain.exception.user;

import com.serverbe.domain.exception.ErrorCode;
import com.serverbe.domain.exception.ErrorKind;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
    NOT_FOUND_USER(ErrorKind.NOT_FOUND, "RUNNER_002", "사용자를 찾을 수 없습니다."),
    FORBIDDEN_USER(ErrorKind.FORBIDDEN, "RUNNER_003", "사용자에 대한 권한이 없습니다.");

    private final ErrorKind kind;
    private final String code;
    private final String message;
}
