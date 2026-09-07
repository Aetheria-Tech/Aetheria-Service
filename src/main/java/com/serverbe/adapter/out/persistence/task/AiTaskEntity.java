package com.serverbe.adapter.out.persistence.task;

import com.serverbe.domain.model.art.vo.Proficiency;
import com.serverbe.domain.model.task.vo.TaskStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 비동기 AI 추론 상태를 관리하는 Task 엔티티
 */
@Entity
@Table(
        name = "ai_generation_tasks",
        indexes = {
                // existsByUserIdAndStatusIn 이 AI 생성 요청마다 실행되므로 복합 인덱스로 풀스캔을 방지합니다.
                @Index(name = "idx_ai_task_user_status", columnList = "user_id, status"),
                // 5분 주기 좀비 스윕(status IN (...) AND updated_at < ?)이 타는 인덱스입니다.
                // 등치 조건인 status 가 선두여야 범위 조건인 updated_at 까지 이어서 좁혀집니다.
                @Index(name = "idx_ai_task_status_updated", columnList = "status, updated_at")
        },
        uniqueConstraints = {
                // "1인 1작업" 규칙의 최종 방어선. Redis 락이 뚫려도 두 번째 INSERT가 DB에서 거부됩니다.
                @UniqueConstraint(name = "uk_ai_task_active_user", columnNames = "active_user_id")
        }
)
@Getter
@DynamicUpdate
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class AiTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false, columnDefinition = "VARCHAR(36)")
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 작업이 <b>진행 중일 때만</b> 소유자 ID를 담고, 종결되면 {@code null}이 되는 컬럼.
     * <p>
     * 이 컬럼에 걸린 유니크 제약이 "사용자당 동시 진행 작업 1건" 규칙을 DB 레벨에서 강제합니다.
     * 애플리케이션의 Redis 락과 중복 조회는 요청이 몰릴 때를 대비한 1·2차 방어일 뿐,
     * 그 사이를 비집고 들어온 동시 요청까지 막아주지는 못하기 때문에 최종 방어선이 필요합니다.
     * </p>
     * <p>
     * 종결된 작업은 {@code null}이 되며, MySQL의 유니크 인덱스는 여러 개의 {@code NULL}을 허용하므로
     * 한 사용자가 과거에 만든 작업 이력은 개수 제한 없이 그대로 남습니다.
     * </p>
     */
    @Column(name = "active_user_id")
    private Long activeUserId;

    @Column(name = "shape", nullable = false)
    private String shape;

    @Enumerated(EnumType.STRING)
    @Column(name = "proficiency", nullable = false)
    private Proficiency proficiency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status;

    @Column(name = "input_s3_uri", length = 500)
    private String inputS3Uri;

    @Column(name = "output_s3_uri", length = 500)
    private String outputS3Uri;

    // FAILED 상태일 때 클라이언트나 백엔드 로깅용으로 원인을 파악하기 위한 컬럼
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "result_art_id")
    private Long resultArtId; // AI 생성이 완료되어 저장된 RunningArt의 ID

    @Builder
    public AiTaskEntity(Long userId, String inputS3Uri, String shape, Proficiency proficiency) {
        this.userId = userId;
        this.status = TaskStatus.PENDING; // 초기 상태는 항상 PENDING
        this.activeUserId = userId;       // 생성 시점부터 '진행 중'이므로 유니크 제약의 대상이 됩니다.
        this.inputS3Uri = inputS3Uri;
        this.shape = shape;
        this.proficiency = proficiency;
    }

    public void updateStatus(TaskStatus status) {
        this.status = status;
    }

    public void markAsCompleted(Long runningArtId) {
        this.status = TaskStatus.COMPLETED;
        this.resultArtId = runningArtId;
        releaseActiveSlot();
    }

    public void markAsFailed(String errorMessage) {
        this.status = TaskStatus.FAILED;
        this.errorMessage = errorMessage;
        releaseActiveSlot();
    }

    public void markAsProcessing(String inputS3Uri, String outputS3Uri) {
        this.status = TaskStatus.PROCESSING;
        this.inputS3Uri = inputS3Uri;
        this.outputS3Uri = outputS3Uri;
        this.activeUserId = this.userId; // 여전히 진행 중이므로 슬롯을 계속 점유합니다.
    }

    /**
     * 작업이 종결되었으므로 사용자의 '진행 중 작업' 슬롯을 반납합니다.
     * 이 호출 이후에야 해당 사용자가 새 작업을 생성할 수 있습니다.
     */
    private void releaseActiveSlot() {
        this.activeUserId = null;
    }
}