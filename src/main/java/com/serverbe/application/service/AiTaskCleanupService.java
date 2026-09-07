package com.serverbe.application.service;

import com.serverbe.application.port.in.task.CleanupZombieTaskUseCase;
import com.serverbe.application.port.out.notification.TaskNotificationPort;
import com.serverbe.application.port.out.task.TaskUpdatePort;
import com.serverbe.application.port.out.task.TaskQueryPort;
import com.serverbe.application.service.helper.AiTaskResourceCleaner;
import com.serverbe.domain.model.task.AiTask;
import com.serverbe.domain.model.task.vo.TaskStatus;
import com.serverbe.application.config.TaskTimeoutPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 무한 로딩 상태에 빠진 좀비(Zombie) AI 작업을 정리하는 자가 치유(Self-Healing) 비즈니스 서비스.
 * <p>
 * 네트워크 단절, 외부 AI 서버(SageMaker)의 장애, 이벤트 유실 등의 이유로
 * 정상적으로 완료(COMPLETED)되거나 실패(FAILED) 처리되지 못한 작업들이 DB에 누적되는 것을 방지합니다.
 * </p>
 */
@Slf4j
@Service
public class AiTaskCleanupService implements CleanupZombieTaskUseCase {

    private final TaskQueryPort taskQueryPort;
    private final TaskUpdatePort taskUpdatePort;
    private final AiTaskResourceCleaner resourceCleaner;
    private final TaskNotificationPort taskNotificationPort;
    private final int taskTimeoutThreshold;

    /**
     * 좀비 작업으로 간주할 대상 상태 목록.
     * (시작 대기 중이거나, AI 서버에서 처리 중인 상태)
     */
    private final List<TaskStatus> zombieStatus = List.of(TaskStatus.PENDING, TaskStatus.PROCESSING);

    /** DB 에 기록할 실패 사유. 도메인 상태 전이와 벌크 UPDATE 가 같은 문구를 써야 하므로 한 곳에서 정의합니다. */
    private static final String TIMEOUT_REASON = "AI 서버 응답 시간 초과 (Timeout)";

    public AiTaskCleanupService(
            TaskQueryPort taskQueryPort,
            TaskUpdatePort taskUpdatePort,
            AiTaskResourceCleaner resourceCleaner,
            TaskNotificationPort taskNotificationPort,
            TaskTimeoutPolicy taskTimeoutPolicy
    ) {
        this.taskQueryPort = taskQueryPort;
        this.taskUpdatePort = taskUpdatePort;
        this.resourceCleaner = resourceCleaner;
        this.taskNotificationPort = taskNotificationPort;
        this.taskTimeoutThreshold = taskTimeoutPolicy.timeoutThresholdMinutes();
    }

    /**
     * 설정된 임계 시간(Timeout)을 초과하여 방치된 좀비 작업들을 일괄적으로 실패(FAILED) 처리합니다.
     * <p>
     * <b>동작 방식:</b><br>
     * 1. 현재 시간 기준으로 설정된 타임아웃 시간(예: 10분) 이상 지난 {@code PENDING} 또는 {@code PROCESSING} 상태의 작업을 조회합니다.<br>
     * 2. 조회된 작업들을 모두 'Timeout' 사유를 포함하여 {@code FAILED} 상태로 변경합니다.
     *    이때 건별로 저장하지 않고 <b>단일 벌크 UPDATE</b> 를 사용합니다. 건별 저장은 어댑터가 id 로
     *    엔티티를 다시 조회한 뒤 갱신하므로 좀비 N건에 문장이 2N개 나가기 때문입니다.<br>
     * 3. 이 과정은 하나의 트랜잭션({@code @Transactional})으로 묶여 부분 업데이트(Partial Update)를 방지합니다.<br>
     * 4. 커밋이 확정된 뒤 S3에 방치된 임시 자원을 정리하고, 대기 중인 클라이언트에게 SSE 실패 알림을 발송합니다.
     * </p>
     */
    @Override
    @Transactional
    public void cleanUpZombieTasks() {
        // 기준 시간 설정: 설정된 시간이 지나도 완료되지 않은 작업은 비정상(좀비)으로 간주합니다.
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(taskTimeoutThreshold);

        // 포트를 통해 조건에 맞는 도메인 객체(AiTask) 목록을 가져옵니다.
        List<AiTask> zombieTasks = taskQueryPort.findZombieTasks(
                zombieStatus,
                timeoutThreshold
        );

        // 좀비 작업이 존재할 경우에만 상태 업데이트 수행
        if (zombieTasks.isEmpty()) {
            return;
        }

        log.warn("[AI Pipeline] {}개의 좀비 Task FAILED 처리", zombieTasks.size());

        // 도메인 상태 전이는 그대로 수행합니다. 커밋 이후의 S3 정리와 SSE 알림이 이 결과를 사용합니다.
        List<AiTask> failedTasks = new ArrayList<>(zombieTasks.size());
        List<String> failedTaskIds = new ArrayList<>(zombieTasks.size());
        for (AiTask task : zombieTasks) {
            failedTasks.add(task.markAsFailed(TIMEOUT_REASON));
            failedTaskIds.add(task.id());
        }

        // DB 반영은 건별 저장이 아니라 한 번의 UPDATE 로 끝냅니다.
        int updated = taskUpdatePort.markFailedInBulk(failedTaskIds, TIMEOUT_REASON);
        log.info("[AI Pipeline] 좀비 Task 벌크 FAILED 처리 완료 - 대상 {}건, 반영 {}건", failedTaskIds.size(), updated);

        finalizeFailedTasksAfterCommit(failedTasks);
    }

    /**
     * 좀비 처리된 작업의 마무리 작업(S3 임시 자원 삭제 + 클라이언트 실패 알림)을 <b>트랜잭션 커밋 이후로</b> 예약합니다.
     * <p>
     * 좀비 작업은 "AI 서버가 끝내 응답하지 않아 입력 프롬프트가 S3에 방치된" 바로 그 상황입니다.
     * 상태만 FAILED로 바꾸고 파일을 그대로 두면 타임아웃될수록 스토리지 비용이 누적되므로 반드시 함께 정리해야 합니다.
     * </p>
     * <p>
     * 알림 또한 여기에 함께 묶습니다. 스케줄러가 상태를 바꾸기만 하고 알리지 않으면, 이미 SseEmitter를 열고
     * 대기 중인 클라이언트는 자신의 SSE 타임아웃까지 무한 로딩에 머물게 됩니다.
     * </p>
     * <p>
     * 둘 다 커밋 이후여야 합니다. 삭제와 발행은 네트워크 I/O이므로 트랜잭션 안에서 수행하면 그 시간만큼 DB 커넥션을
     * 붙잡습니다. 더 중요하게는, 상태 갱신이 롤백되었는데 파일만 지워지거나 클라이언트가 실패 알림을 받아
     * SSE 연결이 닫혀 버리면(터미널 상태 처리) 되돌릴 수 없는 불일치가 남습니다.
     * </p>
     */
    private void finalizeFailedTasksAfterCommit(List<AiTask> failedTasks) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 없이 호출된 경우(테스트 등)에는 즉시 정리합니다.
            failedTasks.forEach(this::finalizeFailedTask);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                failedTasks.forEach(AiTaskCleanupService.this::finalizeFailedTask);
            }
        });
    }

    private void finalizeFailedTask(AiTask failedTask) {
        resourceCleaner.cleanUp(failedTask);
        notifyFailure(failedTask);
    }

    /**
     * 클라이언트에게 작업이 타임아웃으로 종료되었음을 알립니다.
     * <p>
     * 발송 실패는 로그만 남기고 삼킵니다. 상태 갱신은 이미 커밋되어 되돌릴 수 없고,
     * 한 건의 알림 실패가 나머지 좀비 작업의 마무리까지 중단시켜서는 안 되기 때문입니다.
     * 알림을 놓친 클라이언트는 재구독 시점의 DB 재확인(SseNotificationAdapter)으로 최종 상태를 받습니다.
     * </p>
     */
    private void notifyFailure(AiTask failedTask) {
        try {
            // DB에 기록하는 내부 사유와 달리, 사용자에게는 그대로 보여줄 수 있는 문구를 보냅니다.
            taskNotificationPort.notifyTaskFailed(failedTask.id(), "AI 서버 응답 시간이 초과되어 작업이 종료되었습니다.");
        } catch (Exception e) {
            log.error("[AI Pipeline] 좀비 Task 실패 알림 발송에 실패했습니다. - TaskID: {}", failedTask.id(), e);
        }
    }
}
