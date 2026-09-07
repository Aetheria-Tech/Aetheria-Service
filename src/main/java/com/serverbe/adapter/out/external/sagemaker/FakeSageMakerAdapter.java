package com.serverbe.adapter.out.external.sagemaker;

import com.serverbe.application.port.out.sagemaker.SageMakerAsyncPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@Primary
@Profile({"local", "test"})
public class FakeSageMakerAdapter implements SageMakerAsyncPort {

    @Override
    public String invokeAsync(String taskId, String inputS3Uri) {
        log.info("[MOCK] SageMaker 비동기 추론 호출 시뮬레이션 작동 중...");
        log.info("[MOCK] Task ID(inferenceId): {}, 전달받은 Input URI: {}", taskId, inputS3Uri);

        // 실제 AI가 연산하는 것처럼 1초 정도 대기 (필요시 주석 해제)
        // try { Thread.sleep(1000); } catch (InterruptedException e) {}

        // 가짜로 생성된 Output S3 URI를 반환합니다.
        String dummyOutputUri = inputS3Uri.replace("input", "output") + ".out";
        log.info("[MOCK] 생성된 가짜 Output URI 반환: {}", dummyOutputUri);
        
        return dummyOutputUri;
    }
}