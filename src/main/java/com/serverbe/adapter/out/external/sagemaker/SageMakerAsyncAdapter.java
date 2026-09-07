package com.serverbe.adapter.out.external.sagemaker;

import com.serverbe.application.port.out.sagemaker.SageMakerAsyncPort;
import com.serverbe.domain.exception.sagemaker.SageMakerErrorCode;
import com.serverbe.domain.exception.sagemaker.SageMakerException;
import com.serverbe.infrastructure.config.properties.AwsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointAsyncRequest;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointAsyncResponse;

/**
 * @responsibility AWS SageMaker 비동기 엔드포인트(Async Endpoint)를 호출하는 어댑터
 * @implSpec AWS SDK v2를 활용하여 S3 Input URI를 전달하고, SageMaker의 추론을 비동기로 트리거합니다.
 */
@Slf4j
@Component
public class SageMakerAsyncAdapter implements SageMakerAsyncPort {

    private final SageMakerRuntimeClient sageMakerClient;
    private final String endpointName;

    public SageMakerAsyncAdapter(SageMakerRuntimeClient sageMakerClient, AwsProperties awsProperties) {
        this.sageMakerClient = sageMakerClient;
        this.endpointName = awsProperties.sagemaker().endpointName();
    }

    /**
     * @param taskId     우리 시스템의 Task ID. {@code inferenceId}로 함께 실어 보냅니다.
     * @param inputS3Uri S3에 업로드된 요청 데이터의 URI
     * @return 추후 결과물이 저장될 S3 Output URI (SageMaker가 응답으로 알려줌)
     * @implNote 비동기 호출이므로 결과를 기다리지 않고 HTTP 202 Accepted 수준의 응답만 즉시 리턴받습니다.
     * @implSpec {@code inferenceId}를 명시적으로 지정하는 이유는, SageMaker가 이 값을 <b>완료 알림과 실패 알림 모두에</b>
     * 그대로 되돌려주기 때문입니다. 실패 알림에는 결과물 S3 경로가 없을 수 있어 경로 파싱만으로는 대상 작업을
     * 특정할 수 없고, 그 경우 추론 실패가 DB에 기록되지 못한 채 메시지가 DLQ로 빠집니다.
     */
    @Override
    public String invokeAsync(String taskId, String inputS3Uri) {
        InvokeEndpointAsyncRequest request = InvokeEndpointAsyncRequest.builder()
                .endpointName(endpointName)
                .inferenceId(taskId) // 콜백에서 대상 작업을 특정하기 위한 식별자
                .inputLocation(inputS3Uri) // 읽어야 할 S3 경로
                .contentType("application/json") // S3에 있는 파일의 타입
                .build();

        try {
            InvokeEndpointAsyncResponse response = sageMakerClient.invokeEndpointAsync(request);

            // SageMaker 비동기 엔드포인트는 호출 직후 결과물이 저장될 예정인 S3 URI를 미리 알려줍니다.
            String outputLocation = response.outputLocation();
            log.info("[SageMaker Invoke] 비동기 추론 요청 성공 - 예상 Output 위치: {}", outputLocation);
            return outputLocation;

            // 1. SageMaker 서버/서비스 측 에러 (예: 엔드포인트 없음, 권한 부족, 모델 컨테이너 에러 등)
        } catch (software.amazon.awssdk.services.sagemakerruntime.model.SageMakerRuntimeException e) {
            log.error("[SageMaker Invoke Error] SageMaker 서버 거절/오류 - Input: {}, 상태코드: {}, 원인: {}",
                    inputS3Uri, e.statusCode(), e.awsErrorDetails().errorMessage());
            throw new SageMakerException(SageMakerErrorCode.SAGE_MAKER_ERROR_CODE, "SageMaker 서버 에러: " + e.awsErrorDetails().errorMessage());

            // 2. 네트워크 단절, 타임아웃 등 클라이언트 측 에러
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            log.error("[SageMaker Invoke Error] 네트워크 타임아웃 및 통신 실패 - Input: {}, 원인: {}",
                    inputS3Uri, e.getMessage());
            throw new SageMakerException(SageMakerErrorCode.SAGE_MAKER_ERROR_CODE, "SageMaker 네트워크 에러: " + e.getMessage());

            // 3. 기타 런타임 에러
        } catch (Exception e) {
            log.error("[SageMaker Invoke Error] 알 수 없는 시스템 오류 - Input: {}, 원인: {}", inputS3Uri, e.getMessage());
            throw new SageMakerException(SageMakerErrorCode.SAGE_MAKER_ERROR_CODE, "알 수 없는 SageMaker 호출 오류");
        }
    }
}