package com.serverbe.application.service;

import com.serverbe.application.port.in.dto.task.TaskSubscription;
import com.serverbe.application.port.out.task.TaskQueryPort;
import com.serverbe.domain.exception.ai.AiErrorCode;
import com.serverbe.domain.exception.ai.AiException;
import com.serverbe.domain.model.art.vo.Proficiency;
import com.serverbe.domain.model.task.AiTask;
import com.serverbe.domain.model.task.vo.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link SseSubscribeService}는 {@link com.serverbe.application.port.in.notification.SseSubscribeUseCase}의 구현체입니다.
 * <p>
 * 이제 이 서비스의 책임은 <b>인가와 상태 스냅샷</b>뿐입니다. 커넥션 수립과 이벤트 전송은 웹 어댑터의
 * {@code SseEmitterRegistry}가 맡으므로 여기서 검증하지 않습니다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SseSubscribeServiceTest {

    @Mock
    private TaskQueryPort taskQueryPort;

    private SseSubscribeService sseSubscribeService;

    private static final String TASK_ID = "task-abc";
    private static final Long OWNER_ID = 1L;

    @BeforeEach
    void setUp() {
        sseSubscribeService = new SseSubscribeService(taskQueryPort);
    }

    @Test
    @DisplayName("성공: 본인이 요청한 Task를 구독하면 현재 상태 스냅샷을 반환한다")
    void subscribe_Success() {
        // given
        AiTask task = AiTask.createPending(OWNER_ID, "HEART", Proficiency.BEGINNER);
        given(taskQueryPort.findById(TASK_ID)).willReturn(Optional.of(task));

        // when
        TaskSubscription result = sseSubscribeService.subscribe(OWNER_ID, TASK_ID);

        // then
        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.currentStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(result.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("성공: 이미 완료된 Task를 구독하면 종결 상태와 결과 ID가 함께 실려 온다")
    void subscribe_Success_AlreadyCompleted() {
        // given: 구독 직전에 작업이 끝난 경우. 이 스냅샷이 없으면 알림이 유실된다.
        AiTask completed = new AiTask(
                TASK_ID, OWNER_ID, "HEART", Proficiency.BEGINNER, TaskStatus.COMPLETED,
                null, null, null, null, null, 777L);
        given(taskQueryPort.findById(TASK_ID)).willReturn(Optional.of(completed));

        // when
        TaskSubscription result = sseSubscribeService.subscribe(OWNER_ID, TASK_ID);

        // then
        assertThat(result.isTerminal()).isTrue();
        assertThat(result.resultArtId()).isEqualTo(777L);
    }

    @Test
    @DisplayName("실패: 존재하지 않는 Task를 구독하려고 하면 NOT_FOUND_AITASK 예외가 발생한다")
    void subscribe_Fail_NotFound() {
        // given
        given(taskQueryPort.findById(TASK_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sseSubscribeService.subscribe(OWNER_ID, TASK_ID))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("errorCode", AiErrorCode.NOT_FOUND_AITASK);
    }

    @Test
    @DisplayName("실패: 타인의 Task를 구독하려고 하면 USER_IS_NOT_OWNER_OF_TASK 예외가 발생한다")
    void subscribe_Fail_NotOwner() {
        // given
        Long strangerId = 999L;
        AiTask task = AiTask.createPending(OWNER_ID, "HEART", Proficiency.BEGINNER);
        given(taskQueryPort.findById(TASK_ID)).willReturn(Optional.of(task));

        // when & then
        assertThatThrownBy(() -> sseSubscribeService.subscribe(strangerId, TASK_ID))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("errorCode", AiErrorCode.USER_IS_NOT_OWNER_OF_TASK);
    }
}
