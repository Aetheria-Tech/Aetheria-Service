package com.serverbe.application.service;

import com.serverbe.application.port.in.dto.task.AiNotificationCommand;
import com.serverbe.application.port.in.task.RetrieveAiResultUseCase;
import com.serverbe.application.port.out.notification.TaskNotificationPort;
import com.serverbe.application.port.out.task.TaskQueryPort;
import com.serverbe.application.port.out.task.TaskUpdatePort;
import com.serverbe.application.service.helper.AiTaskResourceCleaner;
import com.serverbe.domain.exception.server.AsyncRaceConditionException;
import com.serverbe.domain.exception.server.DataIntegrityViolationException;
import com.serverbe.domain.model.art.vo.Proficiency;
import com.serverbe.domain.model.task.AiTask;
import com.serverbe.domain.model.task.vo.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AiNotificationService}는 외부 AI 워커의 콜백을 처리하는 지점으로,
 * 이 브랜치의 동시성 제어와 비용 감축 요구사항이 실제로 맞물리는 곳입니다.
 * <p>
 * 따라서 정상 경로보다 <b>중복 메시지, 순서 역전, 종결된 작업에 대한 지연 콜백</b> 같은
 * 실패·경합 시나리오를 중점적으로 검증합니다.
 * </p>
 * <p>
 * SQS 메시지에서 Task ID를 특정하는 부분은 전송 형식의 문제이므로
 * {@code adapter.in.messaging.AiNotificationSqsListenerTest}가 따로 다룹니다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class AiNotificationServiceTest {

    @Mock
    private TaskQueryPort taskQueryPort;
    @Mock
    private TaskUpdatePort taskUpdatePort;
    @Mock
    private RetrieveAiResultUseCase retrieveAiResultUseCase;
    @Mock
    private TaskNotificationPort taskNotificationPort;
    @Mock
    private AiTaskResourceCleaner resourceCleaner;

    @InjectMocks
    private AiNotificationService aiNotificationService;

    private static final String TASK_ID = "task-abc-123";
    private static final Long USER_ID = 1L;

    private AiTask taskWithStatus(TaskStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new AiTask(
                TASK_ID, USER_ID, "HEART", Proficiency.BEGINNER, status,
                "s3://bucket/inputs/" + TASK_ID + ".json",
                "s3://bucket/outputs/" + TASK_ID + ".json.out",
                null, now, now, null
        );
    }

    private AiNotificationCommand completedNotification() {
        return new AiNotificationCommand(TASK_ID, true, null);
    }

    private AiNotificationCommand failedNotification() {
        return new AiNotificationCommand(TASK_ID, false, "Insufficient GPU capacity");
    }

    // ================= 정상 경로 =================

    @Test
    @DisplayName("성공: PROCESSING 상태에서 완료 메시지를 받으면 결과 회수 파이프라인을 실행한다")
    void completedMessage_OnProcessingTask_TriggersResultRetrieval() {
        // given
        AiTask task = taskWithStatus(TaskStatus.PROCESSING);
        given(taskQueryPort.findByIdForUpdate(TASK_ID)).willReturn(Optional.of(task));

        // when
        aiNotificationService.handleNotification(completedNotification());

        // then
        verify(retrieveAiResultUseCase).processTaskResult(task);
    }

    @Test
    @DisplayName("성공: PROCESSING 상태에서 실패 메시지를 받으면 FAILED로 기록하고 S3 자원을 정리한 뒤 실패를 알린다")
    void failedMessage_OnProcessingTask_MarksFailedAndCleansUpS3() {
        // given
        AiTask task = taskWithStatus(TaskStatus.PROCESSING);
        given(taskQueryPort.findByIdForUpdate(TASK_ID)).willReturn(Optional.of(task));

        // when
        aiNotificationService.handleNotification(failedNotification());

        // then
        ArgumentCaptor<AiTask> savedCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(taskUpdatePort).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(savedCaptor.getValue().errorMessage()).contains("Insufficient GPU capacity");

        // 추론이 실패해도 S3 객체는 그대로 남아 과금되므로 반드시 정리되어야 한다
        ArgumentCaptor<AiTask> cleanedCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(resourceCleaner).cleanUp(cleanedCaptor.capture());
        assertThat(cleanedCaptor.getValue().status()).isEqualTo(TaskStatus.FAILED);

        verify(taskNotificationPort).notifyTaskFailed(TASK_ID, "Insufficient GPU capacity");
        verify(retrieveAiResultUseCase, never()).processTaskResult(any(AiTask.class));
    }

    // ================= 멱등성 (중복 메시지 방어) =================

    @Test
    @DisplayName("멱등성: 이미 COMPLETED된 작업에 완료 메시지가 중복 도착하면 결과 회수를 다시 실행하지 않는다")
    void duplicateCompletedMessage_DoesNotReprocess() {
        // given
        AiTask completedTask = taskWithStatus(TaskStatus.COMPLETED);
        given(taskQueryPort.findByIdForUpdate(TASK_ID)).willReturn(Optional.of(completedTask));

        // when
        aiNotificationService.handleNotification(completedNotification());

        // then: 재처리되면 러닝 아트가 중복 저장되고 완료 알림도 두 번 나간다
        verify(retrieveAiResultUseCase, never()).processTaskResult(any(AiTask.class));
        verify(taskUpdatePort, never()).save(any(AiTask.class));
        verify(taskNotificationPort, never()).notifyTaskCompleted(anyString(), anyString());
    }

    @Test
    @DisplayName("멱등성: 이미 COMPLETED된 작업에 실패 메시지가 뒤늦게 도착해도 상태를 FAILED로 덮어쓰지 않는다")
    void lateFailedMessage_DoesNotOverwriteCompletedTask() {
        // given
        AiTask completedTask = taskWithStatus(TaskStatus.COMPLETED);
        given(taskQueryPort.findByIdForUpdate(TASK_ID)).willReturn(Optional.of(completedTask));

        // when
        aiNotificationService.handleNotification(failedNotification());

        // then: 아트가 정상 생성됐는데 사용자에게 실패 알림이 가는 상황을 막아야 한다
        verify(taskUpdatePort, never()).save(any(AiTask.class));
        verify(taskNotificationPort, never()).notifyTaskFailed(anyString(), anyString());
    }

    @Test
    @DisplayName("비용 방어: 종결된 작업에 콜백이 뒤늦게 도착하면 재처리는 막되 고아 결과물은 정리한다")
    void lateMessage_OnFinishedTask_StillCleansUpOrphanResources() {
        // given: 타임아웃으로 이미 FAILED 처리된 뒤, SageMaker가 결과물을 기록하고 콜백을 보낸 상황
        AiTask failedTask = taskWithStatus(TaskStatus.FAILED);
        given(taskQueryPort.findByIdForUpdate(TASK_ID)).willReturn(Optional.of(failedTask));

        // when
        aiNotificationService.handleNotification(completedNotification());

        // then
        verify(retrieveAiResultUseCase, never()).processTaskResult(any(AiTask.class));
        verify(resourceCleaner).cleanUp(failedTask);
    }

    // ================= 경합 조건 / 안전망 =================

    @Test
    @DisplayName("경합 조건: 아직 PENDING 상태면 예외를 던져 재시도를 유도한다")
    void pendingTask_ThrowsToTriggerSqsRetry() {
        // given: 요청 스레드가 아직 PROCESSING 저장을 마치지 못한 상태에서 콜백이 먼저 도착
        given(taskQueryPort.findByIdForUpdate(TASK_ID)).willReturn(Optional.of(taskWithStatus(TaskStatus.PENDING)));

        // when & then
        assertThatThrownBy(() -> aiNotificationService.handleNotification(completedNotification()))
                .isInstanceOf(AsyncRaceConditionException.class);

        verify(retrieveAiResultUseCase, never()).processTaskResult(any(AiTask.class));
    }

    @Test
    @DisplayName("안전망: DB에 Task가 존재하지 않으면 예외를 전파해 메시지가 DLQ로 이동하게 한다")
    void missingTask_ThrowsDataIntegrityViolation() {
        // given
        given(taskQueryPort.findByIdForUpdate(TASK_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> aiNotificationService.handleNotification(completedNotification()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
