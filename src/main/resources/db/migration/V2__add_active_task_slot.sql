-- =====================================================================
-- V2: "사용자당 동시 진행 작업 1건" 규칙의 DB 레벨 최종 방어선 추가
--
-- 애플리케이션의 Redis 락(5초)과 중복 조회는 1·2차 방어일 뿐입니다.
-- 지오코딩이 지연되어 PENDING 행이 기록되기 전에 락이 만료되면 그 사이를 비집고
-- 두 건의 요청이 동시에 성사될 수 있고, 그대로 SageMaker 호출 비용이 두 배가 됩니다.
-- 진행 중일 때만 값이 채워지는 active_user_id 컬럼에 유니크 제약을 걸어
-- 경합이 뚫리더라도 두 번째 INSERT가 DB에서 거부되도록 합니다.
--
-- MySQL의 유니크 인덱스는 여러 개의 NULL을 허용하므로, 종결된 작업(NULL)은
-- 개수 제한 없이 이력으로 그대로 남습니다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 유니크 제약 생성 전 기존 데이터 정리
--
-- 지금까지는 제약이 없었으므로 한 사용자가 여러 건의 진행 중 작업을 갖고 있을 수 있습니다.
-- 그 상태로 백필하면 유니크 인덱스 생성이 실패하므로, 사용자별로 가장 최근 1건만 남기고
-- 나머지는 FAILED로 종결시킵니다. (어차피 콜백을 받지 못한 채 방치된 좀비 작업들입니다)
--
-- created_at이 동일한 행이 있어도 id를 tiebreaker로 사용하므로 정확히 1건만 남습니다.
-- ---------------------------------------------------------------------
UPDATE ai_generation_tasks t
    JOIN (SELECT user_id,
                 SUBSTRING_INDEX(
                         GROUP_CONCAT(id ORDER BY created_at DESC, id DESC),
                         ',', 1
                 ) AS keep_id
          FROM ai_generation_tasks
          WHERE status IN ('PENDING', 'PROCESSING')
          GROUP BY user_id) d ON t.user_id = d.user_id
SET t.status        = 'FAILED',
    t.error_message = 'active_user_id 제약 도입에 따른 중복 진행 작업 정리 (V2 마이그레이션)',
    t.updated_at    = NOW(6)
WHERE t.status IN ('PENDING', 'PROCESSING')
  AND t.id <> d.keep_id;

-- ---------------------------------------------------------------------
-- 2) 컬럼 추가
-- ---------------------------------------------------------------------
ALTER TABLE ai_generation_tasks
    ADD COLUMN active_user_id BIGINT NULL;

-- ---------------------------------------------------------------------
-- 3) 기존 진행 중 작업 백필
--
-- 이 단계가 빠지면 마이그레이션 시점에 이미 진행 중이던 작업들은 active_user_id가
-- NULL로 남아 슬롯을 점유하지 않게 되고, 해당 사용자는 제약의 보호를 받지 못합니다.
-- ---------------------------------------------------------------------
UPDATE ai_generation_tasks
SET active_user_id = user_id
WHERE status IN ('PENDING', 'PROCESSING');

-- ---------------------------------------------------------------------
-- 4) 제약 및 인덱스 생성
-- ---------------------------------------------------------------------

-- 사용자당 동시 진행 작업 1건을 강제하는 최종 방어선
CREATE UNIQUE INDEX uk_ai_task_active_user
    ON ai_generation_tasks (active_user_id);

-- existsByUserIdAndStatusIn 이 AI 생성 요청마다 실행되므로 풀스캔을 방지
CREATE INDEX idx_ai_task_user_status
    ON ai_generation_tasks (user_id, status);
