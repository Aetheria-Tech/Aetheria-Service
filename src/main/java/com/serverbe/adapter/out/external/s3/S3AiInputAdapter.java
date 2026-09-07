package com.serverbe.adapter.out.external.s3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.application.port.out.dto.ai.AiPromptCommand;
import com.serverbe.application.port.out.s3.S3AiInputPort;
import com.serverbe.domain.exception.s3.S3ErrorCode;
import com.serverbe.domain.exception.s3.S3Exception;
import com.serverbe.infrastructure.config.properties.AwsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * AI 추론(Inference)을 위한 요청 데이터를 AWS S3에 업로드하고 관리하는 외부 서비스 어댑터.
 * <p>
 * <b>역할 및 책임 (Responsibility):</b><br>
 * 애플리케이션과 비동기 AI 워커(예: SageMaker) 사이의 데이터 브릿지 역할을 수행합니다.
 * 또한, 분산 트랜잭션(Saga Pattern) 환경에서 파이프라인 실패 시 발생하는 스토리지 낭비를 막기 위해
 * 찌꺼기 파일을 즉각적으로 정리하는 보상 트랜잭션(Compensation) 로직을 담당합니다.
 * </p>
 */
@Slf4j
@Component
public class S3AiInputAdapter implements S3AiInputPort {

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final String inputBucketName;

    /**
     * S3AiInputAdapter 생성자.
     * 외부 프로퍼티 클래스에서 필요한 S3 버킷 이름만 추출하여 초기화합니다.
     *
     * @param s3Client      AWS SDK v2 S3 클라이언트 빈
     * @param objectMapper  프롬프트 직렬화에 사용할 Jackson 매퍼
     * @param awsProperties AWS 관련 설정 정보를 담고 있는 프로퍼티 객체
     */
    public S3AiInputAdapter(S3Client s3Client, ObjectMapper objectMapper, AwsProperties awsProperties) {
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.inputBucketName = awsProperties.s3().aiInputBucket();
    }

    /**
     * AI 모델에게 전달할 프롬프트(JSON) 데이터를 S3 버킷에 업로드합니다.
     * <p>
     * <b>동시성 및 충돌 방지:</b><br>
     * 파일명 충돌(Overwrite)을 원천 차단하기 위해 고유한 식별자인 {@code taskId}를
     * S3 Object Key로 사용합니다.
     * </p>
     *
     * @param taskId AI 작업을 식별하는 고유 UUID (파일명으로 매핑됨)
     * @param prompt AI 모델이 읽어갈 요청 파라미터
     * @return 타 시스템에서 즉시 참조 가능한 표준 S3 URI (예: {@code s3://bucket-name/inputs/taskId.json})
     * @throws S3Exception S3 네트워크 타임아웃, 권한 거절 등 업로드 실패 시 발생
     * @implNote 직렬화는 저장소의 표현 형식이므로 애플리케이션이 아니라 이 어댑터의 책임입니다.
     */
    @Override
    public String uploadInputJson(String taskId, AiPromptCommand prompt) {
        String promptJson = serialize(taskId, prompt);

        // S3에 저장될 파일 경로 및 이름 (예: inputs/123e4567-e89b-12d3...json)
        String objectKey = "inputs/" + taskId + ".json";

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(inputBucketName)
                .key(objectKey)
                .contentType("application/json") // AI 워커가 명확히 JSON임을 인지하도록 MIME 타입 명시
                .build();

        try {
            // S3에 문자열(JSON) 데이터를 바로 업로드
            s3Client.putObject(putObjectRequest, RequestBody.fromString(promptJson));

            // SageMaker 등 외부 AI 플랫폼이 읽을 수 있는 표준 S3 URI 형식으로 조합하여 반환
            String s3Uri = String.format("s3://%s/%s", inputBucketName, objectKey);
            log.info("[S3 Upload] AI 비동기 입력 데이터 업로드 완료: {}", s3Uri);
            return s3Uri;

        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            log.error("[S3 Upload Error] S3 서버 거절/오류 - TaskID: {}, 상태코드: {}, 원인: {}",
                    taskId, e.statusCode(), e.awsErrorDetails().errorMessage());
            throw new S3Exception(S3ErrorCode.S3_UPLOAD_ERROR, "S3 서버 에러: " + e.awsErrorDetails().errorMessage());
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            log.error("[S3 Upload Error] S3 네트워크 타임아웃 및 통신 실패 - TaskID: {}, 원인: {}",
                    taskId, e.getMessage());
            throw new S3Exception(S3ErrorCode.S3_UPLOAD_ERROR, "S3 네트워크 에러: " + e.getMessage());
        } catch (Exception e) {
            log.error("[S3 Upload Error] 알 수 없는 시스템 오류 - TaskID: {}, 원인: {}", taskId, e.getMessage());
            throw new S3Exception(S3ErrorCode.S3_UPLOAD_ERROR, "알 수 없는 S3 업로드 오류");
        }
    }

    /**
     * 추론 요청 파라미터를 AI 워커가 읽을 JSON 문자열로 직렬화합니다.
     *
     * @implNote 레코드 컴포넌트 이름이 그대로 JSON 키가 됩니다. 키가 바뀌면 추론 스크립트가
     * 값을 찾지 못한 채 조용히 기본값으로 동작하므로 {@code S3AiInputAdapterTest}가 키 집합을 고정합니다.
     */
    private String serialize(String taskId, AiPromptCommand prompt) {
        try {
            return objectMapper.writeValueAsString(prompt);
        } catch (JsonProcessingException e) {
            log.error("[S3 Upload Error] 프롬프트 직렬화 실패 - TaskID: {}, 원인: {}", taskId, e.getMessage());
            throw new S3Exception(S3ErrorCode.S3_UPLOAD_ERROR, "프롬프트 직렬화 실패: " + e.getMessage());
        }
    }

    /**
     * AI 파이프라인 실패 시 실행되는 보상 트랜잭션(Compensation) 로직입니다.
     * <p>
     * <b>동작 원리:</b><br>
     * SageMaker 호출 등 후속 작업이 실패했을 때, 이미 S3에 업로드되어 고아(Orphan) 상태가 된
     * 파일을 즉시 삭제하여 불필요한 AWS 스토리지 과금을 방지합니다. 전달받은 S3 URI를 파싱하여
     * 정확한 Object Key를 역산출한 뒤 삭제 명령을 수행합니다.
     * </p>
     *
     * @param s3Uri 삭제할 파일의 전체 S3 URI (예: {@code s3://bucket-name/inputs/taskId.json})
     * @throws IllegalArgumentException 시스템에 설정된 버킷 정보와 URI의 버킷 정보가 불일치할 경우
     * @throws S3Exception S3 삭제 과정에서 네트워크/권한 오류가 발생한 경우
     */
    @Override
    public void deleteInputFile(String s3Uri) {
        // 1. S3 URI에서 Object Key만 추출하기 위한 Prefix 생성 (예: s3://my-bucket/)
        String prefix = String.format("s3://%s/", inputBucketName);

        // 2. 외부에서 잘못된 URI가 들어왔을 경우에 대한 방어 로직
        if (!s3Uri.startsWith(prefix)) {
            log.error("[S3 Delete Error] 잘못된 S3 URI 형식 또는 버킷 불일치 - URI: {}", s3Uri);
            throw new IllegalArgumentException("지원하지 않거나 버킷이 일치하지 않는 S3 URI 입니다: " + s3Uri);
        }

        // 3. Prefix를 제거하여 순수 Object Key 추출 (예: inputs/123e4567.json)
        String objectKey = s3Uri.substring(prefix.length());

        DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                .bucket(inputBucketName)
                .key(objectKey)
                .build();

        try {
            s3Client.deleteObject(deleteObjectRequest);
            log.info("[S3 Delete] 보상 트랜잭션 작동 - S3 찌꺼기 파일 삭제 완료: {}", s3Uri);

        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            log.error("[S3 Delete Error] S3 서버 거절/오류 - URI: {}, 상태코드: {}, 원인: {}",
                    s3Uri, e.statusCode(), e.awsErrorDetails().errorMessage());
            throw new S3Exception(S3ErrorCode.S3_DELETE_ERROR, "S3 파일 삭제 서버 에러: " + e.awsErrorDetails().errorMessage());
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            log.error("[S3 Delete Error] S3 네트워크 타임아웃 및 통신 실패 - URI: {}, 원인: {}",
                    s3Uri, e.getMessage());
            throw new S3Exception(S3ErrorCode.S3_DELETE_ERROR, "S3 파일 삭제 네트워크 에러: " + e.getMessage());
        } catch (Exception e) {
            log.error("[S3 Delete Error] 알 수 없는 시스템 오류 - URI: {}, 원인: {}", s3Uri, e.getMessage());
            throw new S3Exception(S3ErrorCode.S3_DELETE_ERROR, "알 수 없는 S3 파일 삭제 오류");
        }
    }
}