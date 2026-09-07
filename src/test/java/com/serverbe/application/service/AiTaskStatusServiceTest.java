package com.serverbe.application.service;

import com.serverbe.application.port.in.dto.task.TaskStatusResult;
import com.serverbe.application.port.out.task.TaskQueryPort;
import com.serverbe.domain.exception.ai.AiErrorCode;
import com.serverbe.domain.exception.ai.AiException;
import com.serverbe.domain.model.art.vo.Proficiency;
import com.serverbe.domain.model.task.AiTask;
import com.serverbe.domain.model.task.vo.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link AiTaskStatusService}는 {@link com.serverbe.application.port.in.task.GetTaskStatusUseCase}만
 * 구현하는 동기 조회입니다. 협력자가 {@link TaskQueryPort} 하나뿐이라 이 테스트도 목이 하나입니다 —
 * 사가 쪽({@link AiGenerationServiceTest})은 여덟 개를 세팅해야 합니다.
 */
@ExtendWith(MockitoExtension.class)
class AiTaskStatusServiceTest {

    @Mock
    private TaskQueryPort taskQueryPort;

    @InjectMocks
    private AiTaskStatusService aiTaskStatusService;

    private static final Long USER_ID = 1L;
    private static final String GENERATED_TASK_ID = "task-uuid-1234";

    @Test
    @DisplayName("성공: 본인의 Task를 조회하면 상태 정보를 반환한다")
    void getTaskStatus_Success() {
        // given
        AiTask task = AiTask.createPending(USER_ID, "HEART", Proficiency.BEGINNER);
        given(taskQueryPort.findById(GENERATED_TASK_ID)).willReturn(Optional.of(task));

        // when
        TaskStatusResult response = aiTaskStatusService.getTaskStatus(GENERATED_TASK_ID, USER_ID);

        // then
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    @DisplayName("실패: 존재하지 않는 Task ID로 조회하면 NOT_FOUND_AITASK 예외가 발생한다")
    void getTaskStatus_Fail_NotFound() {
        // given
        given(taskQueryPort.findById(GENERATED_TASK_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> aiTaskStatusService.getTaskStatus(GENERATED_TASK_ID, USER_ID))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("errorCode", AiErrorCode.NOT_FOUND_AITASK);
    }

    @Test
    @DisplayName("실패: 타인의 Task를 조회하려고 하면 USER_IS_NOT_OWNER_OF_TASK 예외가 발생한다")
    void getTaskStatus_Fail_NotOwner() {
        // given
        Long ownerId = USER_ID;
        Long strangerId = 999L;
        AiTask task = AiTask.createPending(ownerId, "HEART", Proficiency.BEGINNER);
        given(taskQueryPort.findById(GENERATED_TASK_ID)).willReturn(Optional.of(task));

        // when & then
        assertThatThrownBy(() -> aiTaskStatusService.getTaskStatus(GENERATED_TASK_ID, strangerId))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("errorCode", AiErrorCode.USER_IS_NOT_OWNER_OF_TASK);
    }
}
