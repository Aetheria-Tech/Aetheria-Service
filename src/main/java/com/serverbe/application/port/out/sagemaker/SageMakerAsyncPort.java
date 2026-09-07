package com.serverbe.application.port.out.sagemaker;

public interface SageMakerAsyncPort {

    /**
     * 비동기 AI 추론을 요청합니다.
     *
     * @param taskId     우리 시스템의 작업 식별자. 추론 요청의 {@code inferenceId}로 함께 전달되어
     *                   추후 완료/실패 알림에 그대로 실려 돌아오며, 콜백이 어떤 작업의 것인지 특정하는 근거가 됩니다.
     * @param inputS3Uri S3에 업로드된 요청 데이터의 URI
     * @return 추후 결과물이 저장될 S3 Output URI
     */
    String invokeAsync(String taskId, String inputS3Uri);
}
