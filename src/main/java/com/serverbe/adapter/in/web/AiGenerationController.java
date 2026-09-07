package com.serverbe.adapter.in.web;

import com.serverbe.adapter.in.web.dto.art.CreateRunningArtRequest;
import com.serverbe.adapter.in.web.dto.task.TaskStatusResponse;
import com.serverbe.adapter.in.web.response.RestApiResponse;
import com.serverbe.application.port.in.art.InitiateAiGenerationUseCase;
import com.serverbe.application.port.in.task.GetTaskStatusUseCase;
import com.serverbe.domain.model.address.Address;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/running-arts/tasks")
public class AiGenerationController {

    private final GetTaskStatusUseCase getTaskStatusUseCase;
    private final InitiateAiGenerationUseCase initiateAiGenerationUseCase;

    /**
     * @param taskId 생성 시 발급받은 Task ID
     * @param userId 검증용 userID
     * @return 작업 상태 정보 (HTTP 200 OK)
     * @responsibility 프론트엔드가 1~3초 주기로 호출하여 AI 비동기 작업의 진행 상황을 폴링(Polling)합니다.
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<RestApiResponse<TaskStatusResponse>> checkTaskStatus(
            @PathVariable("taskId") String taskId,
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(
                RestApiResponse.success(
                        TaskStatusResponse.from(getTaskStatusUseCase.getTaskStatus(taskId, userId))
                )
        );
    }


    @PostMapping
    public Mono<ResponseEntity<RestApiResponse<String>>> initiateGeneration(
            @Valid @RequestBody CreateRunningArtRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId
    ) {
        new Address(request.startPosition()); // 유효성 검증

        return initiateAiGenerationUseCase.initiateGeneration(
                        userId,
                        request.startPosition(),
                        request.shape(),
                        request.proficiency()
                )
                .subscribeOn(Schedulers.boundedElastic())
                .map(taskId ->
                        ResponseEntity
                                .status(HttpStatus.CREATED)
                                .body(RestApiResponse.created(taskId))
                );
    }
}