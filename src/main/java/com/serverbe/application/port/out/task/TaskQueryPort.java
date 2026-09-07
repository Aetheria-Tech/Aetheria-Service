package com.serverbe.application.port.out.task;

import com.serverbe.domain.model.task.AiTask;
import com.serverbe.domain.model.task.vo.TaskStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TaskQueryPort {
    Optional<AiTask> findById(String taskId);
    List<AiTask> findZombieTasks(List<TaskStatus> statuses, LocalDateTime threshold);
    List<AiTask> findAllByStatus(TaskStatus status);
    Optional<AiTask> findByIdForUpdate(String taskId);
    boolean existsActiveTaskByUserId(Long userId); // 진행 중인 작업 존재 여부 확인
}