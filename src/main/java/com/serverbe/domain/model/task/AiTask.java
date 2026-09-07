package com.serverbe.domain.model.task;

import com.serverbe.domain.exception.ai.AiErrorCode;
import com.serverbe.domain.exception.ai.AiException;
import com.serverbe.domain.model.art.vo.Proficiency;
import com.serverbe.domain.model.task.vo.TaskStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * @responsibility AI 추론 작업의 상태와 데이터를 관리하는 순수 도메인 모델
 * @implNote 불변성(Immutability)을 보장하기 위해 Java Record로 구현되었으며,
 * 상태 변경 시 새로운 인스턴스를 반환합니다.
 */
public record AiTask(
        String id,
        Long userId,
        String shape,
        Proficiency proficiency,
        TaskStatus status,
        String inputS3Uri,
        String outputS3Uri,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long resultArtId
) {

    /**
     * 정적 팩토리 메서드: 최초 PENDING 상태의 인스턴스를 생성합니다.
     *
     * @implNote ID는 null로 세팅되며, 영속성 어댑터(JPA)에서 save() 될 때 자동 할당됩니다.
     */
    public static AiTask createPending(Long userId, String shape, Proficiency proficiency) {
        return new AiTask(
                null, // 신규 생성이므로 id는 null (Adapter의 save 로직에서 null 체크 후 insert 수행)
                userId,
                shape,
                proficiency,
                TaskStatus.PENDING,
                null,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null
        );
    }

    /**
     * 비즈니스 로직: 요청자가 이 Task의 소유자인지 검증합니다. (Tell, Don't Ask 원칙)
     */
    public void validateOwner(Long requestUserId) {
        if (!Objects.equals(this.userId, requestUserId)) {
            throw new AiException(AiErrorCode.USER_IS_NOT_OWNER_OF_TASK);
        }
    }

    /**
     * 아직 외부 AI 서버에 요청이 접수되지 않은 초기 상태인지 판정합니다.
     * <p>
     * SQS 콜백이 도착했는데 이 상태라면, 요청 스레드가 아직 {@code PROCESSING} 저장을 마치지 못한
     * 경합 조건(Race Condition)이므로 콜백 처리를 미루고 재시도를 유도해야 합니다.
     * </p>
     */
    public boolean isPending() {
        return this.status == TaskStatus.PENDING;
    }

    /**
     * 외부 AI 서버의 콜백을 지금 처리해도 되는 상태인지 판정합니다.
     * <p>
     * {@code PROCESSING}일 때만 참입니다. 이미 {@code COMPLETED}/{@code FAILED}로 종결된 작업은
     * 중복 콜백이거나 타임아웃 이후 뒤늦게 도착한 콜백이므로 다시 처리해서는 안 됩니다.
     * </p>
     */
    public boolean isProcessable() {
        return this.status == TaskStatus.PROCESSING;
    }

    /**
     * S3에 남아 있을 수 있는 이 작업의 임시 자원(입력 프롬프트/추론 결과물) 경로를 반환합니다.
     * <p>
     * 아직 생성되지 않아 {@code null}인 경로는 제외되므로, 호출자는 반환된 목록을 그대로 삭제 대상으로 사용할 수 있습니다.
     * </p>
     */
    public List<String> s3ResourceUris() {
        return Stream.of(this.inputS3Uri, this.outputS3Uri)
                .filter(uri -> uri != null && !uri.isBlank())
                .toList();
    }

    // 비즈니스 로직: 상태 변경 시 새로운 Record 인스턴스를 생성하여 반환합니다.

    public AiTask markAsProcessing(String inputS3Uri, String outputS3Uri) {
        return new AiTask(
                this.id,
                this.userId,
                this.shape,
                this.proficiency,
                TaskStatus.PROCESSING,
                inputS3Uri,
                outputS3Uri,
                this.errorMessage,
                this.createdAt,
                LocalDateTime.now(),
                this.resultArtId
        );
    }

    public AiTask markAsCompleted(Long runningArtId) {
        return new AiTask(
                this.id,
                this.userId,
                this.shape,
                this.proficiency,
                TaskStatus.COMPLETED,
                this.inputS3Uri,
                this.outputS3Uri,
                this.errorMessage,
                this.createdAt,
                LocalDateTime.now(),
                runningArtId
        );
    }

    public AiTask markAsFailed(String errorMessage) {
        String truncatedMessage = (errorMessage != null && errorMessage.length() > 1000)
                ? errorMessage.substring(0, 997) + "..."
                : errorMessage;

        return new AiTask(
                this.id,
                this.userId,
                this.shape,
                this.proficiency,
                TaskStatus.FAILED,
                this.inputS3Uri,
                this.outputS3Uri,
                truncatedMessage,
                this.createdAt,
                LocalDateTime.now(),
                this.resultArtId
        );
    }
}