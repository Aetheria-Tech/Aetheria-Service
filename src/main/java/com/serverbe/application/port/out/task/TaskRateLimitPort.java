package com.serverbe.application.port.out.task;

public interface TaskRateLimitPort {
    /**
     * @param userId 사용자 ID
     * @param seconds 차단할 시간(초)
     * @return 락 획득 성공 시 true, 실패 시 false
     */
    boolean tryLock(Long userId, int seconds);
}