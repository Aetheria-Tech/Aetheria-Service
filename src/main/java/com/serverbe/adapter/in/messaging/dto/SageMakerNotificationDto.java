package com.serverbe.adapter.in.messaging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * SageMaker Async Inference 완료 시 발행되는 이벤트 규격.
 * <p>
 * <b>성공과 실패의 페이로드 구조가 다릅니다.</b> 성공 알림에는 결과물 위치({@code responseParameters.outputLocation})가
 * 담기지만, 실패 알림에는 이 필드가 비어 있거나 {@code responseParameters} 자체가 없을 수 있습니다.
 * 반면 {@code inferenceId}는 추론 요청 시 우리가 지정한 값이 성공/실패 모두에 그대로 실려 오므로,
 * 대상 작업을 특정할 때는 이 값을 우선 사용합니다.
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SageMakerNotificationDto(
        String invocationStatus, // "Completed" 또는 "Failed"

        /*
         * 추론 요청 시 InvokeEndpointAsync에 지정한 식별자. 우리 시스템의 Task ID를 그대로 넣어 보내며,
         * SageMaker가 성공/실패 알림 양쪽에 되돌려주므로 Task 특정의 1순위 근거가 됩니다.
         */
        String inferenceId,

        String failureReason,
        ResponseParameters responseParameters
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResponseParameters(
            String contentType,
            String outputLocation // ex: "s3://my-bucket/output/123e4567-e89b-12d3.out"
    ) {}

    /**
     * 추론이 성공했는지 여부를 반환합니다. 상태 문자열 비교 규칙을 이 한 곳으로 모읍니다.
     */
    public boolean isCompleted() {
        return "Completed".equalsIgnoreCase(this.invocationStatus);
    }

    /**
     * 결과물 S3 경로를 null 안전하게 반환합니다.
     * <p>
     * 실패 알림에는 {@code responseParameters}가 없을 수 있어, 직접 접근하면 NPE가 발생합니다.
     * 그 NPE는 메시지를 그대로 DLQ로 보내버려 실패가 DB에 기록되지 못하게 만들므로 여기서 방어합니다.
     * </p>
     *
     * @return 결과물 S3 URI. 알림에 담겨 있지 않으면 {@code null}
     */
    public String outputLocation() {
        return this.responseParameters == null ? null : this.responseParameters.outputLocation();
    }
}
