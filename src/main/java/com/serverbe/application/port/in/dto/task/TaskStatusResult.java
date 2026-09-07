package com.serverbe.application.port.in.dto.task;

import com.serverbe.domain.model.task.AiTask;
import com.serverbe.domain.model.task.vo.TaskStatus;

/**
 * @responsibility AI 작업(Task)의 현재 상태를 외부 계층(어댑터)에 전달하기 위한 애플리케이션 계층의 결과 객체입니다.
 * @param taskId 조회된 AI 작업의 고유 ID
 * @param status 작업의 현재 진행 상태
 * @param resultArtId 작업이 완료되어 생성된 런닝 아트의 ID (완료 전이면 null)
 * @param errorMessage 작업이 실패한 경우의 사유 (실패가 아니면 null)
 * @implNote 인바운드 포트({@link com.serverbe.application.port.in.task.GetTaskStatusUseCase})가 웹 어댑터의 응답
 * DTO를 직접 반환하지 않도록, 애플리케이션 계층 전용 결과 객체로 도메인 모델을 변환합니다.
 * 웹 응답 형태로의 최종 변환은 인바운드 어댑터(컨트롤러)의 책임입니다.
 */
public record TaskStatusResult(
        String taskId,
        TaskStatus status,
        Long resultArtId,
        String errorMessage
) {
    /**
     * @responsibility {@link AiTask} 도메인 모델을 {@link TaskStatusResult} DTO로 변환합니다.
     * @param aiTask 변환할 AI 작업 도메인 엔티티
     * @return 상태 정보가 담긴 DTO 객체
     */
    public static TaskStatusResult from(AiTask aiTask) {
        return new TaskStatusResult(
                aiTask.id(),
                aiTask.status(),
                aiTask.resultArtId(),
                aiTask.errorMessage()
        );
    }
}
