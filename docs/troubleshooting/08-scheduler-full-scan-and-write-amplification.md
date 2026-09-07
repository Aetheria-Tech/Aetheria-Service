# 8. 5분마다 도는 스케줄러의 숨은 비용 — 풀 스캔과 쓰기 증폭

> 요약 · [README — 8. 5분마다 도는 스케줄러의 숨은 비용](../../README.md#8-5분마다-도는-스케줄러의-숨은-비용--풀-스캔과-쓰기-증폭)
> 근거 · [`V4__add_ai_task_sweep_index.sql`](../../src/main/resources/db/migration/V4__add_ai_task_sweep_index.sql) · [`JpaAiTaskRepository.java`](../../src/main/java/com/serverbe/adapter/out/persistence/task/JpaAiTaskRepository.java) · [`AiTaskCleanupService.java`](../../src/main/java/com/serverbe/application/service/AiTaskCleanupService.java)

## 1. 상황

좀비 작업 정리 스케줄러는 5분마다 조용히 돕니다. 하는 일은 단순합니다 —
`status IN ('PENDING','PROCESSING') AND updated_at < (지금 - 10분)`인 작업을 찾아 `FAILED`로 종결시키고,
S3 임시 자원을 정리하고, 대기 중인 클라이언트에 실패를 알립니다.

**장애를 일으키지 않는 코드**입니다. 그래서 아무도 보지 않습니다.

## 2. 증상

증상이 없다는 것이 증상입니다. 실행 한 번이

- **테이블 전체를 읽고**,
- 정리 대상이 N건이면 **문장을 2N개** 내보내고 있었습니다.

종결된 작업(`COMPLETED`·`FAILED`)은 삭제되지 않고 계속 쌓입니다. 즉 **이 비용은 서비스 수명에 비례해
증가**합니다. 사용자가 늘수록, 서비스가 오래될수록 5분마다 읽는 양이 커집니다. 어느 시점에 갑자기
문제가 되기 전까지는 아무 신호도 없습니다.

## 3. 원인 — 읽기

`findZombieTasks`의 술어는 `status IN (...) AND updated_at < ?`인데, 이 테이블의 **어떤 인덱스도 이 조건을
좁혀 주지 못했습니다.**

| 기존 인덱스 | 사용 불가 사유 |
| --- | --- |
| `PRIMARY(id)` | 조건에 `id`가 없음 |
| `uk_ai_task_active_user(active_user_id)` | 조건에 `active_user_id`가 없음 |
| `idx_ai_task_user_status(user_id, status)` | **선두 컬럼이 `user_id`** 라 `status`만으로는 탈 수 없음 |

세 번째가 특히 함정입니다. 인덱스에 `status`가 들어 있으니 쓰일 것 같지만, B-tree 복합 인덱스는
**선두 컬럼부터 순서대로** 좁혀 나갑니다. `user_id` 조건이 없으면 그 인덱스는 시작점을 잡을 수 없습니다.

```sql
EXPLAIN SELECT ... FROM ai_generation_tasks
 WHERE status IN ('PENDING','PROCESSING') AND updated_at < ?;
-- possible_keys: NULL
-- type: ALL          ← 풀 스캔
```

## 4. 원인 — 쓰기

도메인 모델이 **불변 Record**라 상태 전이는 항상 "조회 → 값 이관 → 저장"입니다. 어댑터의 갱신 경로는
id로 엔티티를 **다시 조회한 뒤** 값을 옮겨 담아 저장합니다.

건별 저장을 반복하면 좀비 1건당 `SELECT` + `UPDATE`, 즉 **N건에 문장 2N개**가 나갑니다.
방치된 작업을 한꺼번에 거두는 일에 왕복이 2N번 필요할 이유가 없습니다.

## 5. 검토한 대안

| 대안 | 기각 이유 |
| --- | --- |
| `(updated_at, status)` 순서로 인덱스 | 범위 조건(`<`)이 선두면 **거기서 인덱스 탐색이 끊깁니다.** 뒤따르는 `status`로 더 좁히지 못합니다. 등치가 앞, 범위가 뒤여야 합니다. |
| `updated_at` 단일 인덱스 | 오래된 행 대부분은 이미 종결된 작업입니다. 걸러야 할 양이 그대로 남습니다. |
| 인덱스 대신 조회 주기를 늘린다 | 좀비 감지가 늦어져 사용자가 더 오래 무한 로딩을 봅니다. 문제를 사용자에게 전가합니다. |
| 종결된 작업을 주기적으로 삭제(파티셔닝/아카이빙) | 근본적으로는 맞는 방향이지만 이력 보존 정책부터 정해야 합니다. 인덱스 하나로 해결되는 문제에 먼저 꺼낼 카드가 아닙니다. |
| 벌크 대신 `saveAll()` | Spring Data의 `saveAll`은 결국 건별 `merge`입니다. 문장 수가 줄지 않습니다. |
| 네이티브 쿼리 벌크 UPDATE | JPQL로 표현 가능한 일에 네이티브를 쓸 이유가 없고, 엔티티 이름 변경 시 컴파일 타임에 드러나지 않습니다. |

## 6. 해결

### 6-1. 읽기 — `(status, updated_at)` 복합 인덱스

```sql
CREATE INDEX idx_ai_task_status_updated
    ON ai_generation_tasks (status, updated_at);
```

**컬럼 순서가 전부입니다.** `status`는 등치 조건(`IN`), `updated_at`은 범위 조건(`<`)입니다.
등치 컬럼이 선두여야 인덱스가 각 `status` 값에 대해 시작점을 잡고, 그 안에서 `updated_at` 범위까지
이어서 좁혀 줍니다. 반대로 잡으면 범위 조건에서 탐색이 끊겨 뒤 컬럼이 무의미해집니다.

### 6-2. 쓰기 — 단일 벌크 UPDATE

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
        UPDATE AiTaskEntity t
           SET t.status = com.serverbe.domain.model.task.vo.TaskStatus.FAILED,
               t.errorMessage = :errorMessage,
               t.activeUserId = NULL,
               t.updatedAt = :now
         WHERE t.id IN :ids
        """)
int markFailedInBulk(...);
```

벌크 JPQL은 **ORM을 우회합니다.** 그래서 ORM이 대신 해 주던 세 가지를 직접 챙겨야 합니다.
셋 다 빠뜨렸을 때의 증상이 다르고, 셋 다 **조용히** 실패합니다.

| 챙길 것 | 빠뜨리면 |
| --- | --- |
| `activeUserId = NULL` | 건별 경로에서 `releaseActiveSlot()`이 하던 일입니다. 빠뜨리면 `uk_ai_task_active_user` 슬롯이 점유된 채 남아 **그 사용자는 새 작업을 영영 만들 수 없습니다.** "1인 1작업" 제약이 그대로 족쇄가 됩니다. |
| `updatedAt` 수동 갱신 | 벌크 JPQL은 `@LastModifiedDate` 감사를 발동시키지 않습니다. 갱신하지 않으면 **다음 스윕이 같은 행을 또 집습니다.** 5분마다 같은 일을 반복하는 무한 루프가 됩니다. |
| `clearAutomatically = true` | 벌크는 영속성 컨텍스트를 우회하므로, 남아 있는 낡은 스냅샷이 이후 dirty checking으로 **방금의 갱신을 되돌릴 수 있습니다.** |

`flushAutomatically = true`도 함께 켰습니다. 벌크 실행 전에 보류 중인 변경을 먼저 내보내지 않으면
벌크가 낡은 데이터를 대상으로 돌 수 있습니다.

### 6-3. 도메인 전이는 그대로 남긴다

성능 때문에 도메인 모델을 건너뛰지는 않았습니다.

```java
// 도메인 상태 전이는 그대로 수행합니다. 커밋 이후의 S3 정리와 SSE 알림이 이 결과를 사용합니다.
for (AiTask task : zombieTasks) {
    failedTasks.add(task.markAsFailed(TIMEOUT_REASON));
    failedTaskIds.add(task.id());
}

// DB 반영은 건별 저장이 아니라 한 번의 UPDATE 로 끝냅니다.
int updated = taskUpdatePort.markFailedInBulk(failedTaskIds, TIMEOUT_REASON);

finalizeFailedTasksAfterCommit(failedTasks);
```

**DB 반영만 벌크로 바꾸고 도메인 전이는 유지**했습니다. 커밋 이후의 S3 정리와 SSE 실패 알림이
전이 결과(`failedTasks`)를 사용하기 때문입니다. 여기서 도메인을 건너뛰면 후속 처리가 무엇을 정리하고
누구에게 알려야 하는지 알 수 없게 됩니다.

실패 사유 문구는 `TIMEOUT_REASON` 상수 하나로 모았습니다. 도메인 전이와 벌크 UPDATE가 **서로 다른 문구를
기록하는 일**을 구조적으로 막습니다.

### 6-4. 정리와 알림은 커밋 이후에

`finalizeFailedTasksAfterCommit`은 S3 정리와 SSE 알림을 `afterCommit`으로 미룹니다.
이유는 [3. SQS 콜백 경합 §5-4](03-sqs-callback-race-condition.md#5-4-정리는-반드시-커밋-이후에)와 같습니다 —
트랜잭션 안에서 네트워크 I/O를 하면 DB 커넥션을 그만큼 오래 붙잡고, 롤백되었는데 알림만 나가면
SSE 연결이 터미널 상태로 닫혀 되돌릴 수 없습니다.

## 7. 검증

- **인덱스 적용 확인**

  ```bash
  docker compose exec mysql mysql -uroot -pletmein webflux \
    -e "SHOW INDEX FROM ai_generation_tasks WHERE Key_name='idx_ai_task_status_updated';"
  ```
- **실행 계획 비교** — 이 항목의 핵심 검증입니다. `type`이 `ALL`에서 `range`로, `possible_keys`가
  `NULL`에서 새 인덱스로 바뀌어야 합니다.

  ```bash
  docker compose exec mysql mysql -uroot -pletmein webflux -e "
    EXPLAIN SELECT * FROM ai_generation_tasks
     WHERE status IN ('PENDING','PROCESSING')
       AND updated_at < NOW() - INTERVAL 10 MINUTE\G"
  ```
- **문장 수 확인** — `JPA_SHOW_SQL=true`로 띄우고 스윕을 한 번 돌린 뒤 로그의 `update` 문장 수를 셉니다.
  좀비가 N건이어도 **1개**여야 합니다.
- **슬롯 반납 확인** — 스윕 이후 해당 사용자가 새 작업을 만들 수 있어야 합니다.

  ```bash
  docker compose exec mysql mysql -uroot -pletmein webflux \
    -e "SELECT id, status, active_user_id FROM ai_generation_tasks WHERE user_id = 1;"
  ```
- **재스윕 방지 확인** — 스윕을 두 번 연속 돌렸을 때 두 번째는 대상이 0건이어야 합니다.
  `updated_at`을 갱신하지 않으면 여기서 드러납니다.
- **단위 테스트** — [`AiTaskCleanupServiceTest.java`](../../src/test/java/com/serverbe/application/service/AiTaskCleanupServiceTest.java)

## 8. 남은 과제

- **이 스케줄러는 현재 실행되지 않습니다.** 저장소에 `@EnableScheduling`이 없어 `@Scheduled`가 무시됩니다.
  자세한 내용은 [4. 스케줄러 중복 실행 §7-1](04-scheduler-duplicate-shedlock.md#7-1-스케줄러가-실제로는-한-번도-실행되지-않는다)에 있습니다.
  즉 이 최적화의 효과는 아직 운영에서 측정된 적이 없습니다.
- 종결된 작업이 무한히 쌓이는 구조 자체는 그대로입니다. 인덱스가 읽기 비용을 낮췄을 뿐,
  테이블은 계속 커집니다. 보존 기간 정책과 아카이빙은 별도로 다뤄야 합니다.
