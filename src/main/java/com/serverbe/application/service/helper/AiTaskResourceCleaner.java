package com.serverbe.application.service.helper;

import com.serverbe.application.port.out.s3.S3AiOutputPort;
import com.serverbe.domain.model.task.AiTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 종결된 AI 작업이 S3에 남긴 임시 자원(입력 프롬프트 JSON, 추론 결과물)을 정리하는 공용 헬퍼.
 * <p>
 * <b>도입 배경:</b><br>
 * S3 정리는 원래 '정상 완료' 경로에만 존재했습니다. 그러나 실제로 비용이 새는 지점은 대부분 실패 경로
 * (SageMaker 추론 실패, 타임아웃으로 인한 좀비 작업, 뒤늦게 도착한 고아 결과물)이며, 이 경로들이
 * 여러 서비스에 흩어져 있어 각자 정리 로직을 중복 구현하기 쉽습니다. 그 정리 책임을 이 한 곳으로 모읍니다.
 * </p>
 * <p>
 * <b>삭제 실패 정책:</b><br>
 * 자원 정리는 어디까지나 부가 작업이므로, 삭제에 실패하더라도 예외를 전파하지 않고 로그만 남깁니다.
 * 작업 상태를 기록하는 핵심 로직이 스토리지 정리 실패 때문에 중단되어서는 안 되기 때문입니다.
 * 삭제되지 못한 객체는 S3 Lifecycle 정책이 최종 방어선이 됩니다.
 * </p>
 * <p>
 * <b>트랜잭션 주의:</b><br>
 * 네트워크 I/O이므로 DB 트랜잭션 안에서 호출하면 커넥션을 그만큼 오래 점유합니다.
 * 호출자는 트랜잭션 커밋 이후에 이 컴포넌트를 호출해야 합니다.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiTaskResourceCleaner {

    /**
     * 삭제에 사용하는 포트입니다. {@code S3AiOutputPort#deleteOutput}은 전달받은 S3 URI에서
     * 버킷과 키를 직접 파싱하므로, 입력 버킷/출력 버킷을 가리지 않고 모두 처리할 수 있습니다.
     */
    private final S3AiOutputPort s3AiOutputPort;

    /**
     * 해당 작업이 S3에 남긴 모든 임시 자원을 삭제합니다.
     * <p>
     * 이미 삭제되었거나 애초에 생성되지 않은 객체에 대한 삭제 요청은 S3에서 정상 응답으로 처리되므로,
     * 이 메서드는 몇 번을 호출해도 안전합니다(멱등).
     * </p>
     *
     * @param task 정리 대상 작업. {@code null}이면 아무 동작도 하지 않습니다.
     */
    public void cleanUp(AiTask task) {
        if (task == null) {
            return;
        }

        for (String uri : task.s3ResourceUris()) {
            deleteQuietly(task.id(), uri);
        }
    }

    /**
     * 단일 S3 객체를 삭제하되, 어떤 예외도 밖으로 던지지 않습니다.
     */
    private void deleteQuietly(String taskId, String s3Uri) {
        try {
            s3AiOutputPort.deleteOutput(s3Uri);
        } catch (Exception e) {
            // 어댑터 구현체가 예외를 던지더라도 상위 비즈니스 흐름을 막지 않습니다.
            log.error("[Resource Cleanup] S3 임시 자원 삭제 실패 (Lifecycle 정책 대기) - TaskID: {}, URI: {}", taskId, s3Uri, e);
        }
    }
}
