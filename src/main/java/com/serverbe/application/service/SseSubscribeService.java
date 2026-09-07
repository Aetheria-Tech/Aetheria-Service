package com.serverbe.application.service;

import com.serverbe.application.port.in.dto.task.TaskSubscription;
import com.serverbe.application.port.in.notification.SseSubscribeUseCase;
import com.serverbe.application.port.out.task.TaskQueryPort;
import com.serverbe.domain.exception.ai.AiErrorCode;
import com.serverbe.domain.exception.ai.AiException;
import com.serverbe.domain.model.task.AiTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 클라이언트의 실시간 구독 요청을 인가하는 비즈니스 서비스 구현체.
 * <p>
 * 타인의 작업을 엿보지 못하도록 <b>소유권 검증</b>을 수행하고, 인가와 동시에 현재 상태를 읽어
 * 스냅샷으로 함께 돌려줍니다.
 * </p>
 *
 * @implNote 상태를 함께 돌려주는 것이 핵심입니다. 구독이 시작되기 직전에 작업이 끝나 버리면 완료
 * 이벤트가 이미 지나가 버려 클라이언트는 영원히 기다리게 됩니다. 어댑터는 이 스냅샷을 보고 연결 직후
 * 종료 이벤트를 재생합니다. 이 방어 로직이 예전에는 SSE 어댑터 안에 있었고, 그 탓에 어댑터가
 * {@code TaskQueryPort}를 직접 들고 있었습니다.
 */
@Service
@RequiredArgsConstructor
public class SseSubscribeService implements SseSubscribeUseCase {

    private final TaskQueryPort taskQueryPort;

    /**
     * @param userId 구독을 요청한 클라이언트의 사용자 ID
     * @param taskId 실시간 알림을 받을 AI 작업의 고유 ID
     * @return 인가된 구독 정보와 현재 상태 스냅샷
     * @throws AiException 대상 작업이 존재하지 않거나({@code NOT_FOUND_AITASK}),
     *                     요청한 사용자가 작업의 소유자가 아닌 경우({@code USER_IS_NOT_OWNER_OF_TASK})
     */
    @Override
    public TaskSubscription subscribe(Long userId, String taskId) {
        // 1. Task 존재 여부 확인
        AiTask aiTask = taskQueryPort.findById(taskId)
                .orElseThrow(() -> new AiException(AiErrorCode.NOT_FOUND_AITASK));

        // 2. 권한 검증 (Security/Authorization)
        if (!Objects.equals(aiTask.userId(), userId)) {
            throw new AiException(AiErrorCode.USER_IS_NOT_OWNER_OF_TASK);
        }

        // 3. 인가 시점의 상태를 함께 넘겨 알림 유실을 막는다
        return new TaskSubscription(taskId, aiTask.status(), aiTask.resultArtId());
    }
}
