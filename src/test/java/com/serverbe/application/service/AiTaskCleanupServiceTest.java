package com.serverbe.application.service;

import com.serverbe.application.port.out.notification.TaskNotificationPort;
import com.serverbe.application.port.out.task.TaskQueryPort;
import com.serverbe.application.port.out.task.TaskUpdatePort;
import com.serverbe.domain.model.art.vo.Proficiency;
import com.serverbe.domain.model.task.AiTask;
import com.serverbe.domain.model.task.vo.TaskStatus;
import com.serverbe.application.config.TaskTimeoutPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link AiTaskCleanupService}는 스케줄러가 주기적으로 호출하는
 * {@link com.serverbe.application.port.in.task.CleanupZombieTaskUseCase}의 구현체입니다.
 */
@ExtendWith(MockitoExtension.class)
class AiTaskCleanupServiceTest {

    @Mock
    private TaskQueryPort taskQueryPort;
    @Mock
    private TaskUpdatePort taskUpdatePort;
    @Mock
    private com.serverbe.application.service.helper.AiTaskResourceCleaner resourceCleaner;
    @Mock
    private TaskNotificationPort taskNotificationPort;

    private AiTaskCleanupService aiTaskCleanupService;

    @BeforeEach
    void setUp() {
        aiTaskCleanupService = new AiTaskCleanupService(taskQueryPort, taskUpdatePort, resourceCleaner, taskNotificationPort, new TaskTimeoutPolicy(10));
    }

    private AiTask zombieTask(String id) {
        LocalDateTime old = LocalDateTime.now().minusMinutes(30);
        return new AiTask(id, 1L, "HEART", Proficiency.BEGINNER, TaskStatus.PROCESSING, "s3://in", null, null, old, old, null);
    }

    @Test
    @DisplayName("성공: 좀비 Task를 건별 저장이 아니라 단 한 번의 벌크 UPDATE로 FAILED 처리한다")
    void cleanUpZombieTasks_Success_MarksAllAsFailedInOneBulkUpdate() {
        // given: 건별 save 는 어댑터가 id 로 엔티티를 다시 조회하므로 좀비 N건에 문장이 2N개 나간다.
        List<AiTask> zombies = List.of(zombieTask("zombie-1"), zombieTask("zombie-2"));
        given(taskQueryPort.findZombieTasks(anyList(), any(LocalDateTime.class))).willReturn(zombies);
        given(taskUpdatePort.markFailedInBulk(anyList(), anyString())).willReturn(2);

        // when
        aiTaskCleanupService.cleanUpZombieTasks();

        // then: 벌크 호출은 정확히 1회, 대상 id 는 전부 포함되어야 한다
        ArgumentCaptor<List<String>> idCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskUpdatePort, times(1)).markFailedInBulk(idCaptor.capture(), reasonCaptor.capture());
        assertThat(idCaptor.getValue()).containsExactly("zombie-1", "zombie-2");
        assertThat(reasonCaptor.getValue()).contains("Timeout");

        // 건별 저장 경로는 더 이상 사용하지 않는다
        verify(taskUpdatePort, never()).save(any(AiTask.class));
    }

    @Test
    @DisplayName("성공(무처리): 좀비 Task가 하나도 없으면 저장 로직을 전혀 호출하지 않는다")
    void cleanUpZombieTasks_NoZombies_DoesNothing() {
        // given
        given(taskQueryPort.findZombieTasks(anyList(), any(LocalDateTime.class))).willReturn(List.of());

        // when
        aiTaskCleanupService.cleanUpZombieTasks();

        // then
        verify(taskUpdatePort, never()).markFailedInBulk(anyList(), anyString());
        verify(taskUpdatePort, never()).save(any());
        verify(resourceCleaner, never()).cleanUp(any(AiTask.class));
        verify(taskNotificationPort, never()).notifyTaskFailed(anyString(), anyString());
    }

    @Test
    @DisplayName("실패: 저장 중 예외가 발생하면 삼키지 않고 그대로 전파하여 @Transactional 롤백을 유도한다")
    void cleanUpZombieTasks_Fail_SaveThrows_PropagatesException() {
        // given
        given(taskQueryPort.findZombieTasks(anyList(), any(LocalDateTime.class))).willReturn(List.of(zombieTask("zombie-1")));
        given(taskUpdatePort.markFailedInBulk(anyList(), anyString())).willThrow(new RuntimeException("DB 커넥션 끊김"));

        // when & then
        assertThatThrownBy(() -> aiTaskCleanupService.cleanUpZombieTasks())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB 커넥션 끊김");
    }

    @Test
    @DisplayName("비용 방어: 좀비 Task를 FAILED 처리할 때 S3에 방치된 임시 자원도 함께 정리한다")
    void cleanUpZombieTasks_AlsoCleansUpS3Resources() {
        // given: 좀비 작업은 "AI 서버가 끝내 응답하지 않아 입력 프롬프트가 S3에 방치된" 바로 그 상황이다.
        // 상태만 FAILED로 바꾸고 파일을 두면 타임아웃될수록 스토리지 비용이 누적된다.
        List<AiTask> zombies = List.of(zombieTask("zombie-1"), zombieTask("zombie-2"));
        given(taskQueryPort.findZombieTasks(anyList(), any(LocalDateTime.class))).willReturn(zombies);

        // when
        aiTaskCleanupService.cleanUpZombieTasks();

        // then
        ArgumentCaptor<AiTask> captor = ArgumentCaptor.forClass(AiTask.class);
        verify(resourceCleaner, times(2)).cleanUp(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(task -> assertThat(task.status()).isEqualTo(TaskStatus.FAILED));
    }

    @Test
    @DisplayName("실시간성: 좀비 Task를 FAILED 처리하면 대기 중인 클라이언트에게 SSE 실패 알림을 발송한다")
    void cleanUpZombieTasks_NotifiesClientOfFailure() {
        // given: 상태만 바꾸고 알리지 않으면 이미 SseEmitter를 열고 있는 클라이언트는
        // 자신의 SSE 타임아웃까지 무한 로딩에 머문다. (TaskTimeoutScheduler Javadoc이 보장한다고 적은 지점)
        List<AiTask> zombies = List.of(zombieTask("zombie-1"), zombieTask("zombie-2"));
        given(taskQueryPort.findZombieTasks(anyList(), any(LocalDateTime.class))).willReturn(zombies);

        // when
        aiTaskCleanupService.cleanUpZombieTasks();

        // then
        verify(taskNotificationPort).notifyTaskFailed(eq("zombie-1"), anyString());
        verify(taskNotificationPort).notifyTaskFailed(eq("zombie-2"), anyString());
    }

    @Test
    @DisplayName("견고성: 한 건의 알림 발송이 실패해도 나머지 좀비 작업의 마무리는 계속된다")
    void cleanUpZombieTasks_NotificationFailure_DoesNotAbortRemainingTasks() {
        // given: 상태 갱신은 이미 커밋되어 되돌릴 수 없으므로, 알림 실패는 삼키고 진행해야 한다.
        List<AiTask> zombies = List.of(zombieTask("zombie-1"), zombieTask("zombie-2"));
        given(taskQueryPort.findZombieTasks(anyList(), any(LocalDateTime.class))).willReturn(zombies);
        willThrow(new RuntimeException("Redis 발행 실패"))
                .given(taskNotificationPort).notifyTaskFailed(eq("zombie-1"), anyString());

        // when & then: 예외가 밖으로 새어 나오지 않는다
        aiTaskCleanupService.cleanUpZombieTasks();

        verify(resourceCleaner, times(2)).cleanUp(any(AiTask.class));
        verify(taskNotificationPort).notifyTaskFailed(eq("zombie-2"), anyString());
    }
}
