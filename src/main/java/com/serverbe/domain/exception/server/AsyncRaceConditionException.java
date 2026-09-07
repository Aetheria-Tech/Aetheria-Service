package com.serverbe.domain.exception.server;

import com.serverbe.domain.exception.BusinessException;
import com.serverbe.domain.exception.ErrorCode;

/**
 * 비동기 파이프라인에서 상태 업데이트 경합(Race Condition)이 발생하여
 * SQS 등 메시지 큐의 재시도(Retry)를 유도하기 위해 던지는 예외입니다.
 */
public class AsyncRaceConditionException extends BusinessException {
    
    public AsyncRaceConditionException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}