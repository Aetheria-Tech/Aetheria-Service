package com.serverbe.adapter.out.persistence.task;

import com.serverbe.domain.model.task.vo.TaskStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface JpaAiTaskRepository extends JpaRepository<AiTaskEntity, String> {

    /**
     * 보안을 위해 클라이언트 폴링 시 본인의 Task가 맞는지 ID와 UserID를 동시에 검증합니다.
     */
    Optional<AiTaskEntity> findByIdAndUserId(String id, Long userId);

    /**
     * 특정 상태(예: PROCESSING)에 있는 모든 Task 목록을 조회합니다.
     * 스케줄러가 결과 회수를 위해 진행 중인 작업들을 찾을 때 사용합니다.
     */
    List<AiTaskEntity> findAllByStatus(TaskStatus status);

    /**
     * 동시성 제어를 위한 비관적 락(쓰기 락) 조회
     * 다른 트랜잭션이 이 Row에 접근하지 못하고 대기하게 만듭니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM AiTaskEntity t WHERE t.id = :id")
    Optional<AiTaskEntity> findByIdForUpdate(@Param("id") String id);

    boolean existsByUserIdAndStatusIn(Long userId, List<TaskStatus> statuses);

    /**
     * 좀비 작업들을 한 번의 UPDATE 로 일괄 실패 처리합니다.
     * <p>
     * 건별로 도메인 객체를 저장하면 어댑터가 id 로 엔티티를 다시 조회한 뒤 갱신하므로
     * 좀비 N건에 문장이 2N개 나갑니다. 스윕은 방치된 작업을 한꺼번에 거두는 일이라
     * 건별 상태 전이가 필요 없고, 벌크 UPDATE 한 번이면 충분합니다.
     * </p>
     * <p>
     * 세 가지를 직접 명시해야 합니다.<br>
     * - {@code activeUserId = NULL}: 건별 경로에서 {@code AiTaskEntity.releaseActiveSlot()}이 하던 일입니다.
     *   빠뜨리면 {@code uk_ai_task_active_user} 슬롯이 계속 점유되어 그 사용자는 새 작업을 만들 수 없습니다.<br>
     * - {@code updatedAt}: 벌크 JPQL 은 {@code @LastModifiedDate} 감사를 발동시키지 않습니다.
     *   갱신하지 않으면 다음 스윕이 같은 행을 또 집습니다.<br>
     * - {@code clearAutomatically}: 벌크 갱신은 영속성 컨텍스트를 우회하므로, 남아 있는 낡은 스냅샷이
     *   이후 dirty checking 으로 갱신을 되돌릴 수 있습니다.
     * </p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AiTaskEntity t
               SET t.status = com.serverbe.domain.model.task.vo.TaskStatus.FAILED,
                   t.errorMessage = :errorMessage,
                   t.activeUserId = NULL,
                   t.updatedAt = :now
             WHERE t.id IN :ids
            """)
    int markFailedInBulk(@Param("ids") List<String> ids,
                         @Param("errorMessage") String errorMessage,
                         @Param("now") LocalDateTime now);
}