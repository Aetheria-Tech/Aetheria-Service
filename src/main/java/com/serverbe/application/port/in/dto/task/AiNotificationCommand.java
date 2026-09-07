package com.serverbe.application.port.in.dto.task;

/**
 * @param taskId        대상 작업 식별자
 * @param completed     추론이 성공했으면 true, 실패했으면 false
 * @param failureReason 실패 사유. 성공이면 null.
 * @responsibility 외부 AI 워커의 추론 결과 통보를 전송 기술과 무관한 형태로 표현합니다.
 * @implNote SQS 메시지 형식({@code SageMakerNotificationDto})을 이 명령으로 옮기는 일은 인바운드
 * 메시징 어댑터가 합니다. 브로커를 바꾸거나 로컬에서 시뮬레이션할 때 애플리케이션은 손대지 않습니다.
 */
public record AiNotificationCommand(String taskId, boolean completed, String failureReason) {
}
