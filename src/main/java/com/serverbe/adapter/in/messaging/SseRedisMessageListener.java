package com.serverbe.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.adapter.in.web.sse.SseEmitterRegistry;
import com.serverbe.application.port.out.dto.notification.TaskNotificationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * @responsibility Redis Pub/Sub 채널을 구독해 다른 인스턴스가 발행한 작업 알림을 받아들입니다.
 * @implSpec 이 클래스는 <b>인바운드</b> 어댑터입니다. 메시지가 밖에서 들어와 애플리케이션 쪽으로
 * 흐릅니다. 이전에는 아웃바운드 패키지에 놓여 있어 방향이 뒤집혀 있었습니다.
 * @implNote 예외를 밖으로 내보내지 않습니다. Redis 리스너 컨테이너의 스레드에서 예외가 새어 나가면
 * 잘못된 메시지 하나가 이후 모든 알림 수신을 멈춰 세울 수 있습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseRedisMessageListener implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SseEmitterRegistry sseEmitterRegistry;

    /**
     * @param message Redis로부터 수신한 원본 메시지 (바이트 배열 본문 및 채널 정보 포함)
     * @param pattern 구독 중인 채널 패턴 (패턴 매칭 미사용 시 채널명과 동일)
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);

            TaskNotificationMessage notification =
                    objectMapper.readValue(body, TaskNotificationMessage.class);

            sseEmitterRegistry.dispatch(notification);

        } catch (Exception e) {
            log.error("[Redis Sub] 메시지 수신/파싱 중 에러 발생. 메시지 처리 실패.", e);
        }
    }
}
