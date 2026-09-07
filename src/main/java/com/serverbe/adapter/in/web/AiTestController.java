package com.serverbe.adapter.in.web;

import com.serverbe.adapter.in.web.response.RestApiResponse;
import com.serverbe.application.port.in.dto.task.AiNotificationCommand;
import com.serverbe.application.port.in.task.HandleAiNotificationUseCase;
import com.serverbe.domain.exception.server.ServerErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * @responsibility 로컬·개발 환경에서 AWS SQS 없이 AI 알림 처리 흐름을 시뮬레이션합니다.
 * @implNote 예전에는 SQS 리스너 빈을 직접 주입받아 메서드를 호출했습니다. 인바운드 웹 어댑터가
 * 다른 인바운드 어댑터를 붙잡는 구조라 포트를 우회하고 있었습니다. 지금은 다른 진입점들과 똑같이
 * {@link HandleAiNotificationUseCase}를 통해 들어갑니다.
 */
@Slf4j
@Tag(name = "AI Test API", description = "로컬/개발 환경 전용 AI 파이프라인 시뮬레이션 도구")
@RestController
@RequestMapping("/api/v1/test/ai/tasks")
@RequiredArgsConstructor
@Profile({"local", "dev"})
public class AiTestController {

    private final HandleAiNotificationUseCase handleAiNotificationUseCase;

    @Operation(summary = "SQS 알림 로컬 시뮬레이션", description = "AWS SQS를 거치지 않고 가짜 추론 결과 통보를 유스케이스에 직접 전달하여 전체 비즈니스 로직(DB 저장, SSE 알림 등)을 테스트합니다.")
    @PostMapping("/{taskId}/mock-sqs-receive")
    public ResponseEntity<RestApiResponse<?>> mockSqsReceive(
            @Parameter(description = "테스트할 Task ID (DB에 존재해야 함)") @PathVariable String taskId,
            @Parameter(description = "SageMaker 상태 (Completed 또는 Failed)") @RequestParam(defaultValue = "Completed") String status
    ) {

        log.info("[TEST] SQS 가짜 알림 생성 및 유스케이스 직접 호출 - Task ID: {}", taskId);

        boolean completed = !"Failed".equalsIgnoreCase(status);
        String failureReason = completed ? null : "Dummy Error: Insufficient GPU capacity";

        try {
            handleAiNotificationUseCase.handleNotification(
                    new AiNotificationCommand(taskId, completed, failureReason));
            return ResponseEntity.ok(RestApiResponse.success("SQS 알림 로컬 테스트 완료! (상태: " + status + ")\n서버 로그와 클라이언트 SSE 응답을 확인해보세요."));
        } catch (Exception e) {
            log.error("[TEST] SQS 로컬 테스트 중 에러 발생", e);
            return ResponseEntity.internalServerError().body(RestApiResponse.fail(ServerErrorCode.INTERNAL_SERVER_ERROR));
        }
    }
}
