package com.serverbe.adapter.in.web.sse;

import com.serverbe.application.port.in.dto.task.TaskSubscription;
import com.serverbe.application.port.out.dto.notification.TaskNotificationMessage;
import com.serverbe.domain.model.task.vo.TaskStatus;
import com.serverbe.infrastructure.config.properties.SseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @responsibility SSE 커넥션 레지스트리의 동작을 고정합니다.
 * @implNote 여기서 지키는 것들은 전부 <b>조용히 깨지는</b> 종류입니다. 해제 콜백을 빠뜨리면 메모리가
 * 서서히 새고, 진행 알림에서 연결을 닫으면 스트림이 조기 종료되며, 탭 하나의 전송 실패가 다른 탭까지
 * 끊어 버려도 에러 응답은 나가지 않습니다.
 */
@DisplayName("SSE 커넥션 레지스트리")
class SseEmitterRegistryTest {

    private SseEmitterRegistry registry;

    private static final String TASK_ID = "task-abc";

    @BeforeEach
    void setUp() {
        registry = new SseEmitterRegistry(new SseProperties(60_000L, "sse-notifications"));
    }

    /** 전송된 이벤트를 기록하는 테스트용 emitter. */
    private static class RecordingEmitter extends SseEmitter {
        private final List<Object> sent = new ArrayList<>();
        private boolean completed = false;
        private boolean failOnSend = false;

        RecordingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failOnSend) {
                throw new IOException("클라이언트 연결이 끊어졌습니다");
            }
            sent.add(builder);
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    @Test
    @DisplayName("등록된 구독자에게 이벤트를 전달한다")
    void 등록된_구독자에게_이벤트를_전달한다() {
        RecordingEmitter emitter = registerRecording();

        registry.dispatch(new TaskNotificationMessage(TASK_ID, "COMPLETED", "777"));

        assertThat(emitter.sent).hasSize(1);
    }

    @Test
    @DisplayName("COMPLETED를 받으면 연결을 닫고 레지스트리에서 제거한다")
    void COMPLETED를_받으면_연결을_닫고_제거한다() {
        RecordingEmitter emitter = registerRecording();

        registry.dispatch(new TaskNotificationMessage(TASK_ID, "COMPLETED", "777"));

        assertThat(emitter.completed).isTrue();
    }

    @Test
    @DisplayName("진행 중 알림에서는 연결을 닫지 않는다")
    void 진행중_알림에서는_연결을_닫지_않는다() {
        RecordingEmitter emitter = registerRecording();

        registry.dispatch(new TaskNotificationMessage(TASK_ID, "PROCESSING", "생성 중"));

        assertThat(emitter.sent).hasSize(1);
        assertThat(emitter.completed).isFalse();
    }

    @Test
    @DisplayName("이 서버에 구독자가 없으면 아무것도 하지 않는다")
    void 구독자가_없으면_아무것도_하지_않는다() {
        registry.dispatch(new TaskNotificationMessage("다른-작업", "COMPLETED", "777"));

        assertThat(registry.subscriberCount("다른-작업")).isZero();
    }

    @Test
    @DisplayName("전송 중 IOException이 나면 그 커넥션만 제거하고 나머지에는 계속 보낸다")
    void 전송_실패한_커넥션만_제거하고_나머지에는_계속_보낸다() {
        RecordingEmitter broken = registerRecording();
        RecordingEmitter healthy = registerRecording();
        broken.failOnSend = true;

        assertThat(registry.subscriberCount(TASK_ID)).isEqualTo(2);

        registry.dispatch(new TaskNotificationMessage(TASK_ID, "COMPLETED", "777"));

        assertThat(healthy.sent).hasSize(1);
        assertThat(healthy.completed).isTrue();
        assertThat(registry.subscriberCount(TASK_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 구독자가 빠지면 맵 항목까지 지워 빈 Set이 남지 않는다")
    void 마지막_구독자가_빠지면_맵_항목까지_지운다() {
        // 커넥션 해제 콜백(onCompletion/onTimeout/onError)이 모두 쓰는 로직입니다.
        // 콜백 자체는 서블릿 비동기 핸들러가 붙어야 발동하므로 로직을 직접 부릅니다.
        SseEmitter first = registry.register(TASK_ID);
        SseEmitter second = registry.register(TASK_ID);
        assertThat(registry.subscriberCount(TASK_ID)).isEqualTo(2);

        registry.remove(TASK_ID, first);
        assertThat(registry.subscriberCount(TASK_ID)).isEqualTo(1);

        registry.remove(TASK_ID, second);
        assertThat(registry.subscriberCount(TASK_ID)).isZero();
    }

    @Test
    @DisplayName("등록된 커넥션에는 해제 콜백이 걸려 있다")
    void 등록된_커넥션에는_해제_콜백이_걸려_있다() {
        SseEmitter emitter = registry.register(TASK_ID);

        // 콜백이 걸려 있지 않으면 연결이 끊겨도 맵에서 빠지지 않아 메모리가 샙니다.
        assertThat(emitter).extracting("timeoutCallback").isNotNull();
        assertThat(emitter).extracting("completionCallback").isNotNull();
        assertThat(emitter).extracting("errorCallback").isNotNull();
    }

    @Test
    @DisplayName("연결 직후 CONNECTED 이벤트를 보낸다")
    void 연결_직후_CONNECTED_이벤트를_보낸다() {
        RecordingEmitter emitter = registerRecording();

        registry.sendConnected(TASK_ID, emitter);

        assertThat(emitter.sent).hasSize(1);
        assertThat(emitter.completed).isFalse();
    }

    @Test
    @DisplayName("구독 직전에 완료된 작업이면 종료 이벤트를 즉시 재생하고 연결을 닫는다")
    void 이미_완료된_작업이면_종료_이벤트를_즉시_재생한다() {
        RecordingEmitter emitter = registerRecording();

        registry.replayTerminalState(
                new TaskSubscription(TASK_ID, TaskStatus.COMPLETED, 777L), emitter);

        assertThat(emitter.sent).hasSize(1);
        assertThat(emitter.completed).isTrue();
    }

    @Test
    @DisplayName("아직 진행 중인 작업이면 아무것도 재생하지 않는다")
    void 진행중인_작업이면_아무것도_재생하지_않는다() {
        RecordingEmitter emitter = registerRecording();

        registry.replayTerminalState(
                new TaskSubscription(TASK_ID, TaskStatus.PROCESSING, null), emitter);

        assertThat(emitter.sent).isEmpty();
        assertThat(emitter.completed).isFalse();
    }

    /** 전송을 관찰할 수 있는 emitter를 레지스트리에 등록한다. */
    private RecordingEmitter registerRecording() {
        RecordingEmitter emitter = new RecordingEmitter();
        registry.register(TASK_ID, emitter);
        return emitter;
    }
}
