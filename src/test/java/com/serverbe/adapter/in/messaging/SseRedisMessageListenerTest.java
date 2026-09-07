package com.serverbe.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.adapter.in.web.sse.SseEmitterRegistry;
import com.serverbe.application.port.out.dto.notification.TaskNotificationMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SSE Redis 메시지 리스너")
class SseRedisMessageListenerTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SseEmitterRegistry sseEmitterRegistry;

    @InjectMocks
    private SseRedisMessageListener listener;

    private DefaultMessage message(String body) {
        return new DefaultMessage(
                "sse-notifications".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("정상 JSON은 레지스트리로 위임한다")
    void 정상_JSON은_레지스트리로_위임한다() {
        listener.onMessage(
                message("{\"taskId\":\"task-abc\",\"eventName\":\"COMPLETED\",\"data\":\"777\"}"), null);

        verify(sseEmitterRegistry).dispatch(
                new TaskNotificationMessage("task-abc", "COMPLETED", "777"));
    }

    @Test
    @DisplayName("깨진 JSON이면 예외를 삼키고 위임하지 않는다")
    void 깨진_JSON이면_예외를_삼키고_위임하지_않는다() {
        // Redis 리스너 스레드로 예외가 새어 나가면 이후 알림 수신이 통째로 멈출 수 있습니다.
        assertThatCode(() -> listener.onMessage(message("{이건 JSON이 아니다"), null))
                .doesNotThrowAnyException();

        verify(sseEmitterRegistry, never()).dispatch(any());
    }
}
