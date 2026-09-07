-- =====================================================================
-- V4: 좀비 작업 스윕(sweep) 전용 인덱스 추가
--
-- AiTaskPersistenceAdapter.findZombieTasks 는 5분 주기 스케줄러가 호출하며
--   WHERE status IN ('PENDING','PROCESSING') AND updated_at < ?
-- 로 조회합니다. 그런데 이 테이블의 기존 인덱스로는 이 술어를 전혀 좁힐 수 없습니다.
--
--   PRIMARY(id)                          - 조건에 id 가 없음
--   uk_ai_task_active_user(active_user_id) - 조건에 active_user_id 가 없음
--   idx_ai_task_user_status(user_id, status) - 선두 컬럼이 user_id 라 사용 불가
--
-- 실제로 EXPLAIN 을 떠 보면 possible_keys 가 NULL, type 이 ALL 로 나옵니다.
-- 즉 5분마다 전체 테이블을 훑고 있으며, 종결된 작업(COMPLETED/FAILED)은 삭제되지 않고
-- 계속 쌓이므로 이 비용은 서비스 수명에 비례해 증가합니다.
--
-- 컬럼 순서가 중요합니다. status 는 등치 조건(IN), updated_at 은 범위 조건(<)이므로
-- 등치 컬럼이 선두여야 인덱스가 범위 조건까지 이어서 좁혀 줍니다.
-- 반대로 (updated_at, status) 로 잡으면 범위 조건에서 인덱스 탐색이 끊깁니다.
-- =====================================================================

CREATE INDEX idx_ai_task_status_updated
    ON ai_generation_tasks (status, updated_at);
