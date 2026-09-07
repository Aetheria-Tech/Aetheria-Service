package com.serverbe.adapter.in.messaging;

import com.serverbe.adapter.in.messaging.dto.SageMakerNotificationDto;
import com.serverbe.application.port.in.dto.task.AiNotificationCommand;
import com.serverbe.application.port.in.task.HandleAiNotificationUseCase;
import com.serverbe.domain.exception.server.AsyncRaceConditionException;
import com.serverbe.domain.exception.server.ServerErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * @responsibility SQS 메시지를 유스케이스 명령으로 옮기는 <b>번역</b>만 검증합니다.
 * @implSpec 처리 규칙(멱등성, 경합 방어, 자원 정리)은 {@code AiNotificationServiceTest}가 다룹니다.
 * @implNote Task ID 특정 로직은 여기 남았습니다. 로컬 시뮬레이션 컨트롤러가 유스케이스를 직접
 * 호출하도록 바뀌면서 그 경로로는 더 이상 이 코드가 커버되지 않기 때문입니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SageMaker SQS 알림 리스너")
class AiNotificationSqsListenerTest {

    @Mock
    private HandleAiNotificationUseCase handleAiNotificationUseCase;

    @InjectMocks
    private AiNotificationSqsListener listener;

    private static final String TASK_ID = "task-abc-123";

    private AiNotificationCommand captureCommand() {
        ArgumentCaptor<AiNotificationCommand> captor = ArgumentCaptor.forClass(AiNotificationCommand.class);
        verify(handleAiNotificationUseCase).handleNotification(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("inferenceId가 있으면 그것을 Task ID로 쓴다")
    void inferenceId가_있으면_그것을_TaskID로_쓴다() {
        listener.receiveAiTaskNotification(new SageMakerNotificationDto(
                "Completed", TASK_ID, null,
                new SageMakerNotificationDto.ResponseParameters(
                        "application/json", "s3://bucket/outputs/전혀-다른-값.json.out")));

        AiNotificationCommand command = captureCommand();
        assertThat(command.taskId()).isEqualTo(TASK_ID);
        assertThat(command.completed()).isTrue();
    }

    @Test
    @DisplayName("inferenceId가 없는 구형 메시지는 결과물 S3 경로에서 Task ID를 복원한다")
    void inferenceId가_없으면_S3_경로에서_TaskID를_복원한다() {
        // SageMaker는 입력 파일명 뒤에 .out 같은 확장자를 덧붙인다.
        listener.receiveAiTaskNotification(new SageMakerNotificationDto(
                "Completed", null, null,
                new SageMakerNotificationDto.ResponseParameters(
                        "application/json", "s3://bucket/outputs/" + TASK_ID + ".json.out")));

        assertThat(captureCommand().taskId()).isEqualTo(TASK_ID);
    }

    @Test
    @DisplayName("결과물 위치가 없는 실패 메시지라도 inferenceId로 대상 작업을 특정한다")
    void 결과물_위치가_없는_실패_메시지도_inferenceId로_특정한다() {
        // 실패 알림은 responseParameters 자체가 없을 수 있다. 경로 파싱에만 의존하면
        // 대상 작업을 못 찾아 실패가 기록되지 못한 채 전량 DLQ로 빠진다.
        listener.receiveAiTaskNotification(new SageMakerNotificationDto(
                "Failed", TASK_ID, "Insufficient GPU capacity", null));

        AiNotificationCommand command = captureCommand();
        assertThat(command.taskId()).isEqualTo(TASK_ID);
        assertThat(command.completed()).isFalse();
        assertThat(command.failureReason()).isEqualTo("Insufficient GPU capacity");
    }

    @Test
    @DisplayName("inferenceId와 결과물 경로가 모두 없으면 UNKNOWN_TASK로 위임한다")
    void 둘_다_없으면_UNKNOWN_TASK로_위임한다() {
        listener.receiveAiTaskNotification(new SageMakerNotificationDto(
                "Failed", null, "알 수 없는 오류", null));

        assertThat(captureCommand().taskId()).isEqualTo("UNKNOWN_TASK");
    }

    @Test
    @DisplayName("유스케이스에서 올라온 예외는 그대로 전파해 SQS 재시도·DLQ가 동작하게 한다")
    void 유스케이스_예외는_그대로_전파된다() {
        // 예외를 삼키면 SQS가 처리 성공으로 보고 메시지를 지운다. DLQ에도 남지 않는다.
        willThrow(new AsyncRaceConditionException(ServerErrorCode.ASYNC_RACE_CONDITION, "아직 PENDING"))
                .given(handleAiNotificationUseCase).handleNotification(any());

        assertThatThrownBy(() -> listener.receiveAiTaskNotification(
                new SageMakerNotificationDto("Completed", TASK_ID, null, null)))
                .isInstanceOf(AsyncRaceConditionException.class);
    }
}
