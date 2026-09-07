package com.serverbe.adapter.out.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.infrastructure.config.properties.SseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * @implNote 발행 JSON의 <b>필드 이름</b>을 고정하는 것이 이 테스트의 핵심입니다. 롤링 배포 중에는
 * 구버전 인스턴스가 발행한 메시지를 신버전이 받으므로, 이름이 바뀌면 그 순간 알림이 조용히 끊깁니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SSE Redis 발행 어댑터")
class SseRedisPublishAdapterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private SseRedisPublishAdapter adapter;

    private static final String CHANNEL = "sse-notifications";

    @BeforeEach
    void setUp() {
        adapter = new SseRedisPublishAdapter(
                redisTemplate, new ObjectMapper(), new SseProperties(60_000L, CHANNEL));
    }

    @Test
    @DisplayName("완료 알림을 설정된 채널로 taskId·eventName·data JSON으로 발행한다")
    void 완료_알림을_설정된_채널로_발행한다() {
        adapter.notifyTaskCompleted("task-abc", "777");

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(channel.capture(), payload.capture());

        assertThat(channel.getValue()).isEqualTo(CHANNEL);
        assertThat(payload.getValue())
                .isEqualTo("{\"taskId\":\"task-abc\",\"eventName\":\"COMPLETED\",\"data\":\"777\"}");
    }

    @Test
    @DisplayName("실패 알림은 eventName이 FAILED이고 사유가 data에 실린다")
    void 실패_알림은_사유가_data에_실린다() {
        adapter.notifyTaskFailed("task-abc", "추론 시간이 초과되었습니다");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(org.mockito.ArgumentMatchers.eq(CHANNEL), payload.capture());

        assertThat(payload.getValue()).isEqualTo(
                "{\"taskId\":\"task-abc\",\"eventName\":\"FAILED\",\"data\":\"추론 시간이 초과되었습니다\"}");
    }

    @Test
    @DisplayName("Redis 발행이 실패해도 호출자에게 전파하지 않는다")
    void Redis_발행_실패는_호출자에게_전파하지_않는다() {
        // 알림 전파 실패가 결과 저장 트랜잭션까지 되돌려서는 안 됩니다.
        willThrow(new RuntimeException("Redis Down"))
                .given(redisTemplate).convertAndSend(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Object.class));

        assertThatCode(() -> adapter.notifyTaskCompleted("task-abc", "777"))
                .doesNotThrowAnyException();
    }
}
