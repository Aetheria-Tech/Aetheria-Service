package com.serverbe.adapter.out.external.sagemaker;

import com.serverbe.domain.exception.sagemaker.SageMakerErrorCode;
import com.serverbe.domain.exception.sagemaker.SageMakerException;
import com.serverbe.infrastructure.config.properties.AwsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointAsyncRequest;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointAsyncResponse;
import software.amazon.awssdk.services.sagemakerruntime.model.SageMakerRuntimeException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * @responsibility 비동기 추론 요청에 실리는 필드, 특히 <b>{@code inferenceId}</b>를 고정합니다.
 * @implSpec {@code inferenceId}에 우리 {@code taskId}를 싣는 것이 이 어댑터의 핵심입니다.
 * SageMaker는 이 값을 <b>완료 알림과 실패 알림 모두에</b> 그대로 되돌려주는데,
 * <b>실패 알림에는 결과물 S3 경로가 없을 수 있습니다.</b> 그래서 이 값이 빠지면 경로 파싱만으로는
 * 대상 작업을 특정할 수 없고, <b>추론 실패가 DB에 기록되지 못한 채 메시지가 DLQ로 빠집니다</b> —
 * 사용자 쪽에서는 작업이 영원히 "진행 중"으로 남습니다.
 * @implNote 본보기는 {@code S3AiInputAdapterTest}입니다 — AWS 클라이언트를 목으로 두고 요청
 * 객체의 조립을 {@code ArgumentCaptor}로 봅니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SageMaker 비동기 호출 어댑터")
class SageMakerAsyncAdapterTest {

    @Mock
    private SageMakerRuntimeClient sageMakerClient;

    private SageMakerAsyncAdapter adapter;

    private static final String ENDPOINT = "aetheria-async-inference-endpoint";
    private static final String TASK_ID = "task-1234";
    private static final String INPUT_URI = "s3://aetheria-ai-requests/inputs/task-1234.json";
    private static final String OUTPUT_URI = "s3://aetheria-ai-output/outputs/task-1234.out";

    @BeforeEach
    void setUp() {
        AwsProperties properties = new AwsProperties(
                null, new AwsProperties.SageMaker(ENDPOINT), null);
        adapter = new SageMakerAsyncAdapter(sageMakerClient, properties);
    }

    private static SageMakerRuntimeException serverError() {
        return (SageMakerRuntimeException) SageMakerRuntimeException.builder()
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorMessage("Endpoint not found").build())
                .build();
    }

    @Test
    @DisplayName("요청에 엔드포인트·입력 경로·타입과 taskId를 inferenceId로 싣는다")
    void 요청에_taskId를_inferenceId로_싣는다() {
        given(sageMakerClient.invokeEndpointAsync(any(InvokeEndpointAsyncRequest.class)))
                .willReturn(InvokeEndpointAsyncResponse.builder().outputLocation(OUTPUT_URI).build());

        assertThat(adapter.invokeAsync(TASK_ID, INPUT_URI)).isEqualTo(OUTPUT_URI);

        ArgumentCaptor<InvokeEndpointAsyncRequest> request =
                ArgumentCaptor.forClass(InvokeEndpointAsyncRequest.class);
        verify(sageMakerClient).invokeEndpointAsync(request.capture());

        assertThat(request.getValue().endpointName()).isEqualTo(ENDPOINT);
        assertThat(request.getValue().inferenceId()).isEqualTo(TASK_ID);
        assertThat(request.getValue().inputLocation()).isEqualTo(INPUT_URI);
        assertThat(request.getValue().contentType()).isEqualTo("application/json");
    }

    @Test
    @DisplayName("SageMaker 서버 거절은 도메인 예외로 번역된다")
    void 서버_거절은_도메인_예외가_된다() {
        given(sageMakerClient.invokeEndpointAsync(any(InvokeEndpointAsyncRequest.class)))
                .willThrow(serverError());

        assertThatThrownBy(() -> adapter.invokeAsync(TASK_ID, INPUT_URI))
                .isInstanceOf(SageMakerException.class)
                .hasFieldOrPropertyWithValue("errorCode", SageMakerErrorCode.SAGE_MAKER_ERROR_CODE);
    }

    @Test
    @DisplayName("네트워크 실패도 같은 도메인 예외로 모인다")
    void 네트워크_실패도_같은_예외로_모인다() {
        given(sageMakerClient.invokeEndpointAsync(any(InvokeEndpointAsyncRequest.class)))
                .willThrow(SdkClientException.create("connect timeout"));

        assertThatThrownBy(() -> adapter.invokeAsync(TASK_ID, INPUT_URI))
                .isInstanceOf(SageMakerException.class)
                .hasFieldOrPropertyWithValue("errorCode", SageMakerErrorCode.SAGE_MAKER_ERROR_CODE);
    }

    /**
     * @implNote 마지막 {@code catch (Exception)}까지 확인합니다. AWS SDK가 아닌 예외가 올라와도
     * <b>애플리케이션 계층으로 프레임워크 예외가 새어 나가지 않아야</b> 합니다.
     */
    @Test
    @DisplayName("예상 못 한 예외도 도메인 예외로 감싸 내보낸다")
    void 예상_못_한_예외도_감싼다() {
        given(sageMakerClient.invokeEndpointAsync(any(InvokeEndpointAsyncRequest.class)))
                .willThrow(new IllegalStateException("알 수 없음"));

        assertThatThrownBy(() -> adapter.invokeAsync(TASK_ID, INPUT_URI))
                .isInstanceOf(SageMakerException.class)
                .hasFieldOrPropertyWithValue("errorCode", SageMakerErrorCode.SAGE_MAKER_ERROR_CODE);
    }
}
