package com.serverbe.application.service;

import com.serverbe.application.port.in.dto.task.AiNotificationCommand;
import com.serverbe.application.port.in.task.HandleAiNotificationUseCase;
import com.serverbe.application.port.in.task.RetrieveAiResultUseCase;
import com.serverbe.application.port.out.notification.TaskNotificationPort;
import com.serverbe.application.port.out.task.TaskQueryPort;
import com.serverbe.application.port.out.task.TaskUpdatePort;
import com.serverbe.application.service.helper.AiTaskResourceCleaner;
import com.serverbe.domain.exception.server.AsyncRaceConditionException;
import com.serverbe.domain.exception.server.DataIntegrityViolationException;
import com.serverbe.domain.exception.server.ServerErrorCode;
import com.serverbe.domain.model.task.AiTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * @responsibility 외부 AI 워커의 추론 결과 통보를 받아 작업을 종결짓습니다.
 * @implSpec 트랜잭션 경계가 이 메서드입니다. 비관적 락 조회와 커밋 이후 정리 등록이 모두 같은
 * 트랜잭션 안에서 일어나야 합니다.
 * @implNote 이 로직은 원래 SQS 리스너 안에 있었습니다. 전송 수단(SQS)과 처리 규칙이 한 클래스에
 * 섞여 있어, 로컬 시뮬레이션용 컨트롤러가 리스너를 직접 주입받는 우회 경로까지 생겼습니다.
 * 지금은 전송은 {@code adapter.in.messaging.AiNotificationSqsListener}가, 규칙은 여기가 맡습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNotificationService implements HandleAiNotificationUseCase {

    private final TaskQueryPort taskQueryPort;
    private final TaskUpdatePort taskUpdatePort;
    private final RetrieveAiResultUseCase retrieveAiResultUseCase;
    private final TaskNotificationPort taskNotificationPort;
    private final AiTaskResourceCleaner resourceCleaner;

    /**
     * 추론 결과 통보를 받아 상태에 맞는 후속 처리를 수행합니다.
     * <p>
     * <b>주요 처리 흐름:</b><br>
     * 1. <b>Task 특정 및 락 획득:</b> 비관적 락으로 조회하여 동시 접근을 직렬화합니다.<br>
     * 2. <b>경합 조건 방어(PENDING):</b> 요청 스레드가 아직 {@code PROCESSING} 저장을 마치지 못한 상태라면
     * 예외를 던져 가시성 타임아웃 이후 재시도되도록 유도합니다.<br>
     * 3. <b>멱등성 보장(종결 상태):</b> 이미 {@code COMPLETED}/{@code FAILED}로 끝난 작업이면 재처리하지 않고 종료합니다.
     * 단, 그 사이 뒤늦게 도착한 고아 결과물이 스토리지 비용으로 남지 않도록 S3 정리는 수행합니다.<br>
     * 4. <b>성공/실패 분기:</b> 성공이면 결과물 등록 파이프라인을 트리거하고, 실패면 상태를 FAILED로 기록한 뒤
     * 임시 자원을 정리하고 클라이언트에 실패 알림을 발송합니다.<br>
     * 5. <b>안전망(DLQ):</b> 처리 중 예외는 그대로 전파하여 최종적으로 DLQ로 이동시켜 메시지 유실을 방지합니다.
     * </p>
     *
     * @param command 추론 결과 통보
     */
    @Override
    @Transactional // 비관적 락을 유지하기 위해 반드시 트랜잭션이 필요합니다!
    public void handleNotification(AiNotificationCommand command) {
        String taskId = command.taskId();

        try {
            AiTask task = getTaskWithLockOrThrow(taskId);

            // [경합 조건 방어] 메인 스레드가 아직 PROCESSING으로 상태를 업데이트하지 못했다면 재시도를 유도합니다.
            if (task.isPending()) {
                throw new AsyncRaceConditionException(
                        ServerErrorCode.ASYNC_RACE_CONDITION,
                        String.format("Task [%s] 가 아직 PENDING 상태입니다. 메인 스레드 업데이트 지연으로 판단하여 예외를 발생시키고 재시도를 유도합니다.", task.id())
                );
            }

            // [멱등성 보장] 이미 종결된 작업은 절대 재처리하지 않고, 남아 있을 수 있는 임시 자원만 정리합니다.
            if (!task.isProcessable()) {
                log.info("[AI Notification] Task {} 는 이미 종결된 작업입니다(현재 상태: {}). 중복/지연 메시지로 판단하여 재처리하지 않습니다.",
                        task.id(), task.status());
                cleanUpResourcesAfterCommit(task);
                return;
            }

            if (command.completed()) {
                retrieveAiResultUseCase.processTaskResult(task);
            } else {
                handleInferenceFailure(task, command.failureReason());
            }

        } catch (AsyncRaceConditionException e) {
            // PENDING 상태로 인한 재시도 유도 예외는 WARN 레벨로 로깅 (정상적인 시스템 흐름 중 하나)
            log.warn("[AI Notification] 비동기 경합 조건(Race Condition) 발생 - {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            // 그 외 심각한 에러는 ERROR 레벨로 로깅
            log.error("[AI Notification] 알림 처리 중 오류 - TaskID: {}", taskId, e);
            throw e;
        }
    }

    /**
     * 추론 자체가 실패했을 때의 후속 처리를 수행합니다.
     * <p>
     * 상태를 FAILED로 기록하고, 이 작업이 S3에 남긴 입력 프롬프트와 실패 출력물을 정리한 뒤,
     * 대기 중인 클라이언트에게 실패 사실을 즉시 알립니다. 추론이 실패해도 S3 객체는 그대로 남아
     * 비용이 계속 발생하므로 <b>정리는 실패 경로에서 더 중요합니다.</b>
     * </p>
     *
     * @param task   FAILED로 전환할 대상 작업 (비관적 락으로 조회된 상태)
     * @param reason 외부 워커가 전달한 실패 사유
     */
    private void handleInferenceFailure(AiTask task, String reason) {
        AiTask failedTask = task.markAsFailed("SageMaker 추론 실패: " + reason);
        taskUpdatePort.save(failedTask);

        cleanUpResourcesAfterCommit(failedTask);
        taskNotificationPort.notifyTaskFailed(failedTask.id(), reason);
    }

    /**
     * S3 임시 자원 정리를 <b>트랜잭션 커밋 이후로</b> 미룹니다.
     * <p>
     * 정리는 네트워크 I/O이므로 트랜잭션 안에서 수행하면 그동안 DB 커넥션과 비관적 락을 붙잡게 됩니다.
     * 또한 트랜잭션이 롤백되어 작업이 여전히 살아 있는데도 파일만 먼저 지워버리는 정합성 문제도 생깁니다.
     * 커밋이 확정된 뒤에만 삭제하도록 동기화를 등록합니다.
     * </p>
     */
    private void cleanUpResourcesAfterCommit(AiTask task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 없이 호출된 경우(테스트 등)에는 즉시 정리합니다.
            resourceCleaner.cleanUp(task);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                resourceCleaner.cleanUp(task);
            }
        });
    }

    /**
     * Task ID를 기반으로 DB에서 도메인 엔티티를 비관적 락과 함께 조회합니다.
     *
     * @param taskId 조회할 AI 작업의 고유 ID
     * @return 조회된 {@link AiTask} 도메인 객체
     * @throws DataIntegrityViolationException 알림은 도착했으나 DB에 해당 Task 기록이 없는 심각한 비동기 정합성 오류 발생 시
     */
    private AiTask getTaskWithLockOrThrow(String taskId) {
        return taskQueryPort.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DataIntegrityViolationException(
                        ServerErrorCode.RESOURCE_NOT_FOUND,
                        String.format("알림 수신 심각한 오류: DB에서 Task ID [%s]를 찾을 수 없습니다. (원인 예상: 트랜잭션 롤백, 백그라운드 스케줄러에 의한 삭제, 또는 동시성 문제)", taskId)
                ));
    }
}
