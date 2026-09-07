package com.serverbe.adapter.out.external.s3;

import com.serverbe.application.port.out.dto.ai.AiPromptCommand;
import com.serverbe.application.port.out.s3.S3AiInputPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬 및 테스트 환경에서 AWS S3 연동 비용과 시간 소모를 줄이기 위한 가짜(Mock) S3 어댑터.
 * <p>
 * 실제 AWS 인프라와 통신하지 않고 성공/실패 동작만 로그로 시뮬레이션합니다.
 * 이를 통해 오프라인 환경에서도 파이프라인과 보상 트랜잭션의 흐름을 완벽하게 테스트할 수 있습니다.
 * </p>
 */
@Slf4j
@Component
@Primary // 로컬 환경에서는 실제 S3 어댑터 대신 이 가짜 빈을 최우선으로 주입받게 합니다.
@Profile({"local", "test", "dev"}) // 🚨 상용(prod)에서는 절대 활성화되지 않음
public class FakeS3Adapter implements S3AiInputPort {

    /**
     * S3 파일 업로드를 시뮬레이션합니다.
     * 실제 네트워크 통신 없이 가짜(Mock) S3 URI를 즉시 생성하여 반환합니다.
     */
    @Override
    public String uploadInputJson(String taskId, AiPromptCommand prompt) {
        log.info("[MOCK] S3 업로드 시뮬레이션 작동 중...");
        log.info("[MOCK] Task ID: {}", taskId);
        log.info("[MOCK] 업로드할 프롬프트: {}", prompt);

        // 실제 S3에 올리지 않고, 가짜 S3 경로(URI)를 즉시 반환합니다.
        String dummyS3Uri = "s3://mock-project-bucket/ai-input/" + taskId + ".json";
        log.info("[MOCK] 생성된 가짜 Input S3 URI 반환: {}", dummyS3Uri);

        return dummyS3Uri;
    }

    /**
     * S3 파일 삭제(보상 트랜잭션)를 시뮬레이션합니다.
     * SageMaker 호출 실패 시 메인 서비스가 이 메서드를 호출하여 파일 정리를 시도하는지 로그로 확인합니다.
     */
    @Override
    public void deleteInputFile(String s3Uri) {
        log.warn("[MOCK] S3 파일 삭제(보상 트랜잭션) 시뮬레이션 작동 중...");
        log.warn("[MOCK] 삭제 요청된 S3 URI: {}", s3Uri);
        log.warn("[MOCK] 실제 삭제 로직은 생략되었으며, 가짜 파일 정리가 완료된 것으로 간주합니다.");
    }
}