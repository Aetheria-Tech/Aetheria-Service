package com.serverbe.application.service;

import com.serverbe.application.port.in.dto.task.TaskStatusResult;
import com.serverbe.application.port.in.task.GetTaskStatusUseCase;
import com.serverbe.application.port.out.task.TaskQueryPort;
import com.serverbe.domain.exception.ai.AiErrorCode;
import com.serverbe.domain.exception.ai.AiException;
import com.serverbe.domain.model.task.AiTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 작업의 진행 상태를 조회하는 서비스.
 * <p>
 * {@link AiGenerationService}에서 갈라져 나왔습니다. 그쪽은 S3 업로드·SageMaker 호출·보상
 * 트랜잭션을 엮는 <b>리액티브 사가</b>이고, 이쪽은 <b>DB에서 한 건 읽어 반환하는 동기 조회</b>입니다.
 * 한쪽이 실패하면 S3 자원을 되돌려야 하고 다른 쪽이 실패하면 404를 주는데, 그 둘은 같은 클래스에
 * 있을 이유가 없습니다.
 * </p>
 * <p>
 * 협력자가 {@link TaskQueryPort} 하나뿐이라는 사실이 그 판단을 뒷받침합니다 — 사가는 협력자
 * 여덟 개를 받습니다.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskStatusService implements GetTaskStatusUseCase {

    private final TaskQueryPort taskQueryPort;

    /**
     * 특정 AI 작업의 현재 진행 상태를 조회합니다.
     * <p>
     * 보안을 위해 해당 작업을 요청한 사용자(Owner)와 현재 조회하려는 사용자가 일치하는지 검증합니다.
     * </p>
     *
     * @param taskId 조회할 AI 작업의 고유 ID
     * @param userId 조회를 요청한 사용자의 ID
     * @return 작업의 현재 상태(상태 코드, S3 결과물 경로 등)를 담은 애플리케이션 계층의 결과 객체
     * @throws AiException 작업 ID가 존재하지 않거나, 본인의 작업이 아닌 경우 발생
     */
    @Override
    public TaskStatusResult getTaskStatus(String taskId, Long userId) {
        AiTask task = taskQueryPort.findById(taskId)
                .orElseThrow(() -> new AiException(AiErrorCode.NOT_FOUND_AITASK));

        // 타인의 작업을 조회할 수 없도록 소유권 검증 로직 수행
        task.validateOwner(userId);

        return TaskStatusResult.from(task);
    }
}
