package com.serverbe.adapter.in.messaging;

import com.serverbe.adapter.in.messaging.dto.SageMakerNotificationDto;
import com.serverbe.application.port.in.dto.task.AiNotificationCommand;
import com.serverbe.application.port.in.task.HandleAiNotificationUseCase;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AWS SQS 큐를 구독하여 비동기 AI 워커(SageMaker)의 작업 완료 및 실패 이벤트를 수신하는 인바운드 어댑터.
 * <p>
 * 이 클래스의 책임은 <b>전송 형식의 번역</b>뿐입니다. SQS 메시지에서 대상 작업을 특정해
 * {@link AiNotificationCommand}로 옮기고, 처리 규칙은 {@link HandleAiNotificationUseCase}에 위임합니다.
 * </p>
 *
 * @implSpec 유스케이스에서 올라온 예외를 <b>절대 삼키지 않습니다.</b> SQS는 예외가 리스너 밖으로
 * 나가는 것을 재시도 신호로 읽습니다. 삼키면 메시지가 삭제되어 DLQ에도 남지 않습니다.
 * @implNote 트랜잭션 경계는 유스케이스 쪽입니다. 여기에는 {@code @Transactional}이 없습니다 —
 * 비관적 락 조회와 커밋 이후 정리가 같은 트랜잭션에 묶여야 하고, 그 범위는 처리 규칙이 정합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiNotificationSqsListener {

    private final HandleAiNotificationUseCase handleAiNotificationUseCase;

    private static final String QUEUE_NAME_PROPERTY = "${aws.sqs.ai-notification-queue-name}";

    /** SageMaker 알림에서 Task ID를 끝내 특정하지 못했을 때 사용하는 표식. */
    private static final String UNKNOWN_TASK = "UNKNOWN_TASK";

    /**
     * SQS 큐에서 SageMaker의 처리 결과 메시지를 수신합니다.
     *
     * @param message SQS로부터 수신하여 역직렬화된 SageMaker 알림 이벤트 DTO
     */
    @SqsListener(QUEUE_NAME_PROPERTY)
    public void receiveAiTaskNotification(SageMakerNotificationDto message) {
        String taskId = extractTaskId(message);

        handleAiNotificationUseCase.handleNotification(new AiNotificationCommand(
                taskId,
                message.isCompleted(),
                message.failureReason()
        ));
    }

    /**
     * SageMaker 알림 메시지에서 대상 Task ID를 특정합니다.
     * <p>
     * <b>추출 우선순위:</b><br>
     * 1. <b>{@code inferenceId}</b> — 추론 요청 시 우리가 Task ID를 그대로 넣어 보낸 값으로, SageMaker가
     * <b>성공/실패 알림 모두에</b> 그대로 되돌려줍니다. 가장 신뢰할 수 있는 출처입니다.<br>
     * 2. <b>결과물 S3 경로 파싱</b> — {@code inferenceId}가 없는 이전 형식의 메시지를 위한 하위 호환 경로입니다.
     * </p>
     * <p>
     * 실패 알림은 성공 알림과 페이로드 구조가 달라 결과물 경로가 아예 없을 수 있습니다. 경로 파싱에만
     * 의존하면 <b>추론 실패가 DB에 기록되지 못한 채 전량 DLQ로 빠지는</b> 문제가 생기므로 {@code inferenceId}를 우선합니다.
     * </p>
     */
    private String extractTaskId(SageMakerNotificationDto message) {
        if (StringUtils.hasText(message.inferenceId())) {
            return message.inferenceId();
        }

        String taskId = extractTaskIdFromUri(message.outputLocation());
        if (UNKNOWN_TASK.equals(taskId)) {
            log.error("[SQS Listener] 메시지에서 Task ID를 특정할 수 없습니다. inferenceId와 결과물 경로가 모두 비어 있습니다. (상태: {}, 사유: {})",
                    message.invocationStatus(), message.failureReason());
        }
        return taskId;
    }

    /**
     * SageMaker가 생성한 결과물 S3 URI 문자열에서 순수한 Task ID(UUID)만을 안전하게 파싱합니다.
     * <p>
     * SageMaker의 Asynchronous Inference는 원본 입력 파일명 뒤에 {@code .out} 등의 추가 확장자를 임의로
     * 붙이는 특성이 있습니다. (예: {@code 1234-abcd.json.out}). 이 메서드는 파일명에서 첫 번째 점({@code .})이
     * 등장하기 전까지의 문자열만 잘라내어 원본 UUID를 복원합니다.
     * </p>
     *
     * @param s3Uri 결과물이 저장된 S3 전체 경로 (예: {@code s3://bucket/outputs/uuid.json.out})
     * @return 파싱된 순수 Task ID 문자열. 유효하지 않은 URI인 경우 {@code UNKNOWN_TASK} 반환
     */
    private String extractTaskIdFromUri(String s3Uri) {
        if (s3Uri == null || !s3Uri.contains("/")) {
            return UNKNOWN_TASK;
        }

        // 1. URL에서 파일명만 추출 (예: "1234abcd-5678.json.out")
        String fileName = s3Uri.substring(s3Uri.lastIndexOf("/") + 1);

        if (!StringUtils.hasText(fileName)) {
            return UNKNOWN_TASK;
        }

        // 2. 첫 번째 점(.)이 등장하는 위치 앞까지만 잘라냅니다.
        return fileName.contains(".") ? fileName.substring(0, fileName.indexOf(".")) : fileName;
    }
}
