package com.serverbe.application.port.in.task;

import com.serverbe.application.port.in.dto.task.TaskStatusResult;

/**
 * AI 생성 작업의 현재 진행 상태를 조회하는 유스케이스 (Inbound Port).
 * <p>
 * 클라이언트(Web/App)가 특정 작업의 상태(대기, 진행 중, 완료, 실패)를
 * 단건으로 확인하고자 할 때 호출하는 비즈니스 진입점입니다.
 * </p>
 */
public interface GetTaskStatusUseCase {

    /**
     * 작업 ID와 사용자 ID를 기반으로 작업 상태를 단건 조회합니다.
     *
     * @param taskId 조회할 AI 작업의 고유 ID
     * @param userId 조회를 요청한 사용자의 ID (타인 작업 조회 방지를 위한 권한 검증용)
     * @return 작업의 현재 상태 정보를 담은 애플리케이션 계층의 결과 객체
     */
    TaskStatusResult getTaskStatus(String taskId, Long userId);
}