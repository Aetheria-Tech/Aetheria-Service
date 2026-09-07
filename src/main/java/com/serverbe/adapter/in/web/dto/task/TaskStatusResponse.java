package com.serverbe.adapter.in.web.dto.task;

import com.serverbe.application.port.in.dto.task.TaskStatusResult;
import com.serverbe.domain.model.task.vo.TaskStatus;

public record TaskStatusResponse(
        String taskId,
        TaskStatus status,
        Long resultArtId,
        String errorMessage
) {
    /**
     * @responsibility 애플리케이션 계층의 결과 객체({@link TaskStatusResult})를 HTTP 응답 DTO로 변환합니다.
     * @implNote 인바운드 어댑터(웹)는 도메인 모델을 직접 다루지 않고, UseCase가 반환한 결과 객체만을 사용합니다.
     */
    public static TaskStatusResponse from(TaskStatusResult result) {
        return new TaskStatusResponse(
                result.taskId(),
                result.status(),
                result.resultArtId(),
                result.errorMessage()
        );
    }
}