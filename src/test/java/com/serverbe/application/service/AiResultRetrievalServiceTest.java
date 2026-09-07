package com.serverbe.application.service;

import com.serverbe.application.port.in.art.RegisterCompletedArtUseCase;
import com.serverbe.application.port.in.dto.art.AiGenerationResultDto;
import com.serverbe.application.port.out.notification.TaskNotificationPort;
import com.serverbe.application.port.out.s3.S3AiOutputPort;
import com.serverbe.application.port.out.task.TaskUpdatePort;
import com.serverbe.domain.model.art.vo.Proficiency;
import com.serverbe.domain.model.task.AiTask;
import com.serverbe.domain.model.task.vo.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AiResultRetrievalService}는 SQS 리스너를 통해 결과 회수 요청을 받는
 * {@link com.serverbe.application.port.in.task.RetrieveAiResultUseCase}의 구현체입니다.
 */
@ExtendWith(MockitoExtension.class)
class AiResultRetrievalServiceTest {

    @Mock
    private TaskUpdatePort taskUpdatePort;
    @Mock
    private S3AiOutputPort s3AiOutputPort;
    @Mock
    private TaskNotificationPort taskNotificationPort;
    @Mock
    private RegisterCompletedArtUseCase registerCompletedArtUseCase;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private com.serverbe.application.service.helper.AiTaskResourceCleaner resourceCleaner;
    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private AiResultRetrievalService aiResultRetrievalService;

    private static final String TASK_ID = "task-xyz";
    private static final Long USER_ID = 1L;
    private static final Long SAVED_ART_ID = 500L;

    @BeforeEach
    void setUp() {
        aiResultRetrievalService = new AiResultRetrievalService(
                taskUpdatePort, s3AiOutputPort, taskNotificationPort, registerCompletedArtUseCase,
                resourceCleaner, transactionTemplate, transactionManager
        );
    }

    /**
     * 실제 스프링 트랜잭션 없이도 콜백 로직이 즉시 실행되도록 TransactionTemplate.execute()를 흉내냅니다.
     */
    private void stubTransactionTemplateExecutesCallback() {
        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private AiTask processingTask() {
        // 실제 SQS 리스너로부터 전달되는 Task는 DB에서 ID가 채번된 상태이므로, 레코드 캐노니컬 생성자로
        // 명시적인 ID를 가진 PROCESSING 상태 Task를 직접 구성합니다.
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return new AiTask(
                TASK_ID, USER_ID, "HEART", Proficiency.BEGINNER, TaskStatus.PROCESSING,
                "s3://bucket/inputs/" + TASK_ID + ".json", "s3://bucket/outputs/" + TASK_ID + ".json",
                null, now, now, null
        );
    }

    @Test
    @DisplayName("성공: 결과물이 없으면(작업 진행 중) 아무 후속 처리도 하지 않는다")
    void processTaskResult_NoOutputYet_DoesNothing() {
        // given
        AiTask task = processingTask();
        given(s3AiOutputPort.downloadOutput(task.outputS3Uri())).willReturn(Optional.empty());

        // when
        aiResultRetrievalService.processTaskResult(task);

        // then
        verify(transactionTemplate, never()).execute(any());
        verify(taskUpdatePort, never()).save(any());
        verify(taskNotificationPort, never()).notifyTaskCompleted(anyString(), anyString());
        verify(taskNotificationPort, never()).notifyTaskFailed(anyString(), anyString());
    }

    @Test
    @DisplayName("성공: 결과물이 있으면 아트를 등록하고 Task를 COMPLETED로 갱신한 뒤 S3를 정리하고 완료 알림을 보낸다")
    void processTaskResult_Success() {
        // given
        AiTask task = processingTask();
        AiGenerationResultDto resultDto = new AiGenerationResultDto(37.5, 127.0, 5000.0, "encodedPolylineData");
        given(s3AiOutputPort.downloadOutput(task.outputS3Uri())).willReturn(Optional.of(resultDto));
        stubTransactionTemplateExecutesCallback();
        given(registerCompletedArtUseCase.registerFromPolyline(
                task.userId(), resultDto.gpx(), task.shape() + " 코스 (" + task.proficiency().getProficiency() + ")", task.shape(), task.proficiency()
        )).willReturn(SAVED_ART_ID);

        // when
        aiResultRetrievalService.processTaskResult(task);

        // then
        ArgumentCaptor<AiTask> captor = ArgumentCaptor.forClass(AiTask.class);
        verify(taskUpdatePort).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(captor.getValue().resultArtId()).isEqualTo(SAVED_ART_ID);

        // S3 임시 자원 정리는 공용 헬퍼로 위임되었으므로, COMPLETED로 확정된 Task가 정리 대상으로 넘어갔는지 확인한다
        ArgumentCaptor<AiTask> cleanedCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(resourceCleaner).cleanUp(cleanedCaptor.capture());
        assertThat(cleanedCaptor.getValue().status()).isEqualTo(TaskStatus.COMPLETED);

        verify(taskNotificationPort).notifyTaskCompleted(task.id(), String.valueOf(SAVED_ART_ID));
        verify(taskNotificationPort, never()).notifyTaskFailed(anyString(), anyString());
    }

    @Test
    @DisplayName("실패: 아트 등록 중 예외가 발생하면 Task가 FAILED로 기록되고 실패 알림이 발송된다")
    void processTaskResult_Fail_RegisterArtThrows() {
        // given
        AiTask task = processingTask();
        AiGenerationResultDto resultDto = new AiGenerationResultDto(37.5, 127.0, 5000.0, "brokenPolyline");
        given(s3AiOutputPort.downloadOutput(task.outputS3Uri())).willReturn(Optional.of(resultDto));
        stubTransactionTemplateExecutesCallback();
        given(registerCompletedArtUseCase.registerFromPolyline(any(), anyString(), anyString(), anyString(), any()))
                .willThrow(new IllegalArgumentException("Polyline 파싱 실패"));

        // when
        aiResultRetrievalService.processTaskResult(task);

        // then
        ArgumentCaptor<AiTask> captor = ArgumentCaptor.forClass(AiTask.class);
        verify(taskUpdatePort).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(captor.getValue().errorMessage()).contains("Polyline 파싱 실패");

        verify(taskNotificationPort).notifyTaskFailed(task.id(), "런닝 아트 등록 중 오류가 발생했습니다.");
        verify(taskNotificationPort, never()).notifyTaskCompleted(anyString(), anyString());

        // 등록에 실패했더라도 S3에 올라간 입력/결과물은 그대로 과금되므로 반드시 정리되어야 한다
        ArgumentCaptor<AiTask> cleanedCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(resourceCleaner).cleanUp(cleanedCaptor.capture());
        assertThat(cleanedCaptor.getValue().status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    @DisplayName("실패(격리): SSE 완료 알림 발송이 실패해도 이미 커밋된 DB 처리 결과에는 영향을 주지 않는다")
    void processTaskResult_NotificationFailure_DoesNotAffectAlreadyCommittedData() {
        // given
        AiTask task = processingTask();
        AiGenerationResultDto resultDto = new AiGenerationResultDto(37.5, 127.0, 5000.0, "encodedPolylineData");
        given(s3AiOutputPort.downloadOutput(task.outputS3Uri())).willReturn(Optional.of(resultDto));
        stubTransactionTemplateExecutesCallback();
        given(registerCompletedArtUseCase.registerFromPolyline(any(), anyString(), anyString(), anyString(), any()))
                .willReturn(SAVED_ART_ID);
        org.mockito.Mockito.doThrow(new RuntimeException("SSE 커넥션 끊김"))
                .when(taskNotificationPort).notifyTaskCompleted(anyString(), anyString());

        // when & then: 알림 실패가 예외로 전파되지 않아야 한다 (Fault Isolation)
        org.assertj.core.api.Assertions.assertThatCode(() -> aiResultRetrievalService.processTaskResult(task))
                .doesNotThrowAnyException();

        // DB 저장(COMPLETED)과 S3 정리는 알림 발송 이전에 이미 끝난 상태이므로 정상 수행되어야 한다
        ArgumentCaptor<AiTask> captor = ArgumentCaptor.forClass(AiTask.class);
        verify(taskUpdatePort).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.COMPLETED);
        verify(resourceCleaner).cleanUp(any(AiTask.class));
    }
}
