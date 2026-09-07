package com.serverbe.domain.exception.sagemaker;

import com.serverbe.domain.exception.ErrorCode;
import com.serverbe.domain.exception.ErrorKind;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SageMakerErrorCode implements ErrorCode {
    SAGE_MAKER_ERROR_CODE(ErrorKind.INTERNAL_ERROR, "SM_001","SageMaker 비동기 호출 중 오류가 발생했습니다."),


    ;



    private final ErrorKind kind;
    private final String code;
    private final String message;
}