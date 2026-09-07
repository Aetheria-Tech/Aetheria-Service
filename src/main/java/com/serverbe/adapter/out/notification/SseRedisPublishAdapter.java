package com.serverbe.adapter.out.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.application.port.out.dto.notification.TaskNotificationMessage;
import com.serverbe.application.port.out.notification.TaskNotificationPort;
import com.serverbe.infrastructure.config.properties.SseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * @responsibility 작업 종결 알림을 Redis Pub/Sub 채널로 발행합니다.
 * @implSpec <b>Scale-out 대응:</b> 알림을 만든 인스턴스와 클라이언트가 SSE로 붙어 있는 인스턴스가
 * 다를 수 있습니다. 채널로 흘려보내면 모든 인스턴스가 받아 자기 구독자에게 전달합니다.
 * @implNote 발행 실패를 호출자에게 전파하지 않습니다. 알림이 못 갔다고 결과 저장 트랜잭션까지
 * 되돌리는 것은 손해가 더 큽니다.
 */
@Slf4j
@Component
public class SseRedisPublishAdapter implements TaskNotificationPort {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String sseChannel;

    public SseRedisPublishAdapter(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            SseProperties sseProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.sseChannel = sseProperties.channel();
    }

    @Override
    public void notifyTaskCompleted(String taskId, String resultArtId) {
        publish(taskId, "COMPLETED", resultArtId);
    }

    @Override
    public void notifyTaskFailed(String taskId, String errorMessage) {
        publish(taskId, "FAILED", errorMessage);
    }

    private void publish(String taskId, String eventName, String data) {
        try {
            String jsonMessage = objectMapper.writeValueAsString(
                    new TaskNotificationMessage(taskId, eventName, data));

            redisTemplate.convertAndSend(sseChannel, jsonMessage);
            log.info("[Redis Pub] SSE 알림 발행 완료 - Task ID: {}, Event: {}", taskId, eventName);
        } catch (Exception e) {
            log.error("[Redis Pub] 알림 발행 실패 - Task ID: {}", taskId, e);
        }
    }
}
