package com.serverbe.adapter.in.web.sse;

import com.serverbe.adapter.in.web.sse.dto.SseNotificationDto;
import com.serverbe.application.port.in.dto.task.TaskSubscription;
import com.serverbe.application.port.out.dto.notification.TaskNotificationMessage;
import com.serverbe.infrastructure.config.properties.SseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * @responsibility 이 인스턴스에 연결된 SSE 커넥션을 보관하고, 도착한 알림을 해당 클라이언트에게 밀어냅니다.
 * @implSpec <b>스레드 안전성:</b> 한 사용자가 여러 탭·기기에서 같은 작업을 구독할 수 있으므로 값은 단일
 * 객체가 아니라 {@link CopyOnWriteArraySet}입니다. 맵은 {@link ConcurrentHashMap}입니다.
 * @implNote 이 클래스가 인바운드 웹 어댑터에 있는 이유는 {@link SseEmitter}가 서블릿 웹 타입이기
 * 때문입니다. 예전에는 아웃바운드 어댑터 하나가 emitter 보관·Redis 발행·Redis 수신 세 가지를 겸했고,
 * 그 탓에 {@code TaskNotificationPort}가 {@code SseEmitter}를 노출했습니다.
 */
@Slf4j
@Component
public class SseEmitterRegistry {

    /**
     * Task ID를 Key로 하여, 해당 Task를 구독 중인 클라이언트들의 SseEmitter 목록을 관리합니다.
     */
    private final Map<String, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    private final Long timeout;

    public SseEmitterRegistry(SseProperties sseProperties) {
        this.timeout = sseProperties.timeout();
    }

    /**
     * @param taskId 구독 대상 작업 식별자
     * @return 등록이 끝난 새 커넥션
     * @responsibility 새 커넥션을 만들어 보관하고, 연결이 끊길 때 스스로 빠지도록 콜백을 겁니다.
     * @implSpec 해제 콜백에서 {@code computeIfPresent}가 빈 Set에 {@code null}을 돌려주어 맵 항목까지
     * 지웁니다. 이걸 빠뜨리면 작업 수만큼 빈 Set이 영원히 쌓입니다.
     */
    public SseEmitter register(String taskId) {
        return register(taskId, new SseEmitter(timeout));
    }

    /**
     * @param emitter 등록할 커넥션
     * @responsibility 커넥션 객체를 직접 받아 등록합니다.
     * @implNote 같은 패키지의 테스트가 전송 동작을 관찰할 수 있도록 열어 둔 이음새입니다.
     * 운영 코드는 {@link #register(String)}만 씁니다.
     */
    SseEmitter register(String taskId, SseEmitter emitter) {
        emitters.computeIfAbsent(taskId, key -> new CopyOnWriteArraySet<>()).add(emitter);

        Runnable onCompletion = () -> emitters.computeIfPresent(taskId, (key, taskEmitters) -> {
            taskEmitters.remove(emitter);
            return taskEmitters.isEmpty() ? null : taskEmitters;
        });

        emitter.onCompletion(onCompletion);
        emitter.onTimeout(onCompletion);
        emitter.onError(e -> onCompletion.run());

        return emitter;
    }

    /**
     * @responsibility 연결 직후 클라이언트에게 연결 성공을 알립니다.
     * @implNote 첫 이벤트를 즉시 흘려보내야 프록시가 응답 헤더를 클라이언트로 내보냅니다.
     */
    public void sendConnected(String taskId, SseEmitter emitter) {
        SseNotificationDto connected =
                SseNotificationDto.processing(taskId, "SSE 연결이 완료되었습니다. AI 생성 대기 중...");

        try {
            emitter.send(buildSseEvent("CONNECTED", connected));
        } catch (IOException e) {
            log.warn("[SSE] 연결 직후 초기 이벤트 전송 실패 - Task ID: {}", taskId);
            remove(taskId, emitter);
        }
    }

    /**
     * @param subscription 인가 시점의 상태 스냅샷
     * @param emitter      방금 등록된 커넥션
     * @responsibility 구독 전에 이미 끝난 작업이라면 종료 이벤트를 즉시 재생하고 연결을 닫습니다.
     * @implSpec 이 재생이 없으면 <b>구독 직전에 완료된 작업</b>의 알림이 유실되어 클라이언트가 영원히
     * 기다립니다. 알림 발행과 구독 사이의 경합을 막는 방어선입니다.
     */
    public void replayTerminalState(TaskSubscription subscription, SseEmitter emitter) {
        if (!subscription.isTerminal()) {
            return;
        }

        String taskId = subscription.taskId();
        SseNotificationDto dto = switch (subscription.currentStatus()) {
            case COMPLETED -> SseNotificationDto.completed(taskId, String.valueOf(subscription.resultArtId()));
            case FAILED -> SseNotificationDto.failed(taskId, "이미 실패한 작업입니다.");
            default -> null;
        };

        try {
            emitter.send(buildSseEvent(subscription.currentStatus().name(), dto));
            emitter.complete();
            log.info("[SSE] 구독 즉시 종결 상태 확인되어 알림 발송 - Task ID: {}, 상태: {}",
                    taskId, subscription.currentStatus());
        } catch (IOException e) {
            log.error("[SSE] 초기 상태 확인 후 알림 전송 실패 - Task ID: {}", taskId, e);
            remove(taskId, emitter);
        }
    }

    /**
     * @param message 다른 인스턴스(또는 자기 자신)가 발행한 알림
     * @responsibility 이 인스턴스에 연결된 구독자들에게 알림을 전달합니다.
     * @implSpec 종결 상태({@code COMPLETED}/{@code FAILED})일 때만 연결을 닫습니다. 진행 알림에서
     * 닫아 버리면 스트림이 조기 종료됩니다.
     * @implNote 전송 중 {@link IOException}이 나면 그 커넥션만 제거하고 나머지에게는 계속 보냅니다.
     * 탭 하나가 닫혔다고 다른 탭의 알림까지 끊겨서는 안 됩니다.
     */
    public void dispatch(TaskNotificationMessage message) {
        Set<SseEmitter> taskEmitters = emitters.get(message.taskId());

        if (taskEmitters == null || taskEmitters.isEmpty()) {
            log.debug("[SSE Sub] 이 서버에는 해당 클라이언트 연결이 없습니다. - Task ID: {}", message.taskId());
            return;
        }

        boolean isTerminalState = "COMPLETED".equals(message.eventName()) || "FAILED".equals(message.eventName());
        SseNotificationDto notification = toNotification(message);

        for (SseEmitter emitter : taskEmitters) {
            try {
                // 객체(DTO)를 그대로 넘기면 Spring이 JSON으로 자동 직렬화 수행
                emitter.send(buildSseEvent(message.eventName(), notification));

                if (isTerminalState) {
                    emitter.complete();
                }
            } catch (IOException e) {
                taskEmitters.remove(emitter);
                log.error("[SSE Sub] 단일 알림 전송 실패 - Task ID: {}", message.taskId(), e);
            }
        }

        log.info("[SSE Sub] 클라이언트 다중 알림 전송 완료! - Task ID: {}, 수신자 수: {}, 연결 종료 여부: {}",
                message.taskId(), taskEmitters.size(), isTerminalState);
    }

    /**
     * @return 이 인스턴스가 해당 작업에 대해 들고 있는 구독자 수 (테스트·진단용)
     */
    public int subscriberCount(String taskId) {
        Set<SseEmitter> taskEmitters = emitters.get(taskId);
        return taskEmitters == null ? 0 : taskEmitters.size();
    }

    private SseNotificationDto toNotification(TaskNotificationMessage message) {
        return switch (message.eventName()) {
            case "COMPLETED" -> SseNotificationDto.completed(message.taskId(), message.data());
            case "FAILED" -> SseNotificationDto.failed(message.taskId(), message.data());
            default -> SseNotificationDto.processing(message.taskId(), message.data());
        };
    }

    /**
     * @responsibility 커넥션 하나를 걷어내고, 그 작업의 마지막 커넥션이었다면 맵 항목까지 지웁니다.
     * @implSpec {@code computeIfPresent}가 빈 Set에 {@code null}을 돌려주는 것이 요점입니다.
     * 이걸 빠뜨리면 구독됐던 작업 수만큼 빈 Set이 영원히 남습니다.
     * @implNote 커넥션 해제 콜백({@code onCompletion}/{@code onTimeout}/{@code onError})이 모두
     * 이 로직을 씁니다. 콜백 자체는 서블릿 비동기 핸들러가 붙어야 발동하므로, 테스트는 로직을
     * 직접 호출해 검증합니다.
     */
    void remove(String taskId, SseEmitter emitter) {
        emitters.computeIfPresent(taskId, (key, taskEmitters) -> {
            taskEmitters.remove(emitter);
            return taskEmitters.isEmpty() ? null : taskEmitters;
        });
    }

    private SseEmitter.SseEventBuilder buildSseEvent(String eventName, Object data) {
        return SseEmitter.event()
                .name(eventName)
                .data(data);
    }
}
