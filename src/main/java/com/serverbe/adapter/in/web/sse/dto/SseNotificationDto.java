package com.serverbe.adapter.in.web.sse.dto;

import com.serverbe.domain.model.task.vo.TaskStatus;
import lombok.Builder;

/**
 * 프론트엔드와 실시간으로 주고받을 SSE(Server-Sent Events) 알림 메시지의 표준 규격 DTO.
 * <p>
 * 일관된 JSON 포맷을 제공하여 프론트엔드에서 상태(status)에 따른 분기 처리 및 
 * 데이터(data) 파싱을 안전하게 수행할 수 있도록 돕습니다.
 * </p>
 */
@Builder
public record SseNotificationDto(
        String taskId,
        TaskStatus status,
        String message,
        Object data // 결과물 URL, 에러 상세 정보 등 유연한 데이터를 담기 위해 Object 사용
) {

    /**
     * [진행 중] 상태의 알림을 생성하는 편의 메서드
     */
    public static SseNotificationDto processing(String taskId, String message) {
        return SseNotificationDto.builder()
                .taskId(taskId)
                .status(TaskStatus.PROCESSING)
                .message(message)
                .data(null)
                .build();
    }

    /**
     * [성공/완료] 상태의 알림을 생성하는 편의 메서드
     *
     * @param data 프론트엔드에서 렌더링할 최종 결과물 (예: 생성된 런닝 아트 DTO 또는 이미지 URL)
     */
    public static SseNotificationDto completed(String taskId, Object data) {
        return SseNotificationDto.builder()
                .taskId(taskId)
                .status(TaskStatus.COMPLETED)
                .message("AI 생성이 완료되었습니다.")
                .data(data)
                .build();
    }

    /**
     * [실패] 상태의 알림을 생성하는 편의 메서드
     *
     * @param errorMessage 클라이언트에게 노출할 실패 사유
     */
    public static SseNotificationDto failed(String taskId, String errorMessage) {
        return SseNotificationDto.builder()
                .taskId(taskId)
                .status(TaskStatus.FAILED)
                .message(errorMessage)
                .data(null)
                .build();
    }
}