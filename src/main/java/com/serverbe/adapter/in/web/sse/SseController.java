package com.serverbe.adapter.in.web.sse;

import com.serverbe.application.annotation.RateLimit;
import com.serverbe.application.annotation.RateLimits;
import com.serverbe.application.port.in.dto.task.TaskSubscription;
import com.serverbe.application.port.in.notification.SseSubscribeUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "AI Task SSE", description = "AI 작업 상태 실시간 알림 (SSE)")
@RestController
@RequestMapping("/api/v1/ai/tasks")
@RequiredArgsConstructor
public class SseController {

    private final SseSubscribeUseCase sseSubscribeUseCase;
    private final SseEmitterRegistry sseEmitterRegistry;

    @Operation(
            summary = "AI 작업 상태 SSE 구독",
            description = "특정 Task ID에 대한 AI 작업 상태(성공, 실패 등)를 실시간으로 구독합니다. (text/event-stream 방식)",
            security = @SecurityRequirement(name = "jwtAuth")
    )
    @RateLimits(
            @RateLimit(target = RateLimit.TargetType.IP, capacity = 1, refillRate = 1)
    )
    @GetMapping(value = "/{taskId}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribe(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "구독할 AI Task ID", example = "task-1234", required = true)
            @PathVariable String taskId
    ) {
        // 1. 인가 + 현재 상태 스냅샷 (애플리케이션 계층)
        TaskSubscription subscription = sseSubscribeUseCase.subscribe(userId, taskId);

        // 2. 커넥션 수립 (웹 어댑터의 책임)
        SseEmitter emitter = sseEmitterRegistry.register(taskId);
        sseEmitterRegistry.sendConnected(taskId, emitter);

        // 3. 구독 직전에 이미 끝난 작업이면 종료 이벤트를 즉시 재생해 알림 유실을 막는다
        sseEmitterRegistry.replayTerminalState(subscription, emitter);

        // SSE 연결의 안정성을 위해 꼭 필요한 헤더들을 주입하여 반환합니다.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache") // 브라우저 캐싱 방지
                .header(HttpHeaders.CONNECTION, "keep-alive") // 연결 유지 명시
                .header("X-Accel-Buffering", "no") // Nginx 등 프록시 서버의 버퍼링 방지 (실시간 전송 보장)
                .body(emitter);
    }
}
