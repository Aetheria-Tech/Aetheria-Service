-- =====================================================================
-- V3: 소셜 계정 자연키 (oauth_id, oauth_provider) 에 유니크 제약 추가
--
-- UserEntity 는 처음부터 이 제약을 선언하고 있었지만 columnNames 에 물리 컬럼명이 아닌
-- 자바 필드명("provider")을 적어 두어 Hibernate 가 어떤 인덱스도 만들지 않았습니다.
-- ddl-auto: validate 는 테이블·컬럼·타입만 검증하고 유니크 인덱스는 보지 않으므로
-- 기동 시점에도 드러나지 않은 채 users 테이블은 PRIMARY KEY(id) 하나만 가진 상태였습니다.
--
-- 그동안 중복을 막아 온 것은 UserDataSyncManager 의 "조회 후 없으면 삽입" 로직뿐입니다.
-- 같은 계정으로 최초 로그인이 동시에 두 번 들어오면 둘 다 조회에서 빈 결과를 받고 둘 다
-- INSERT 하며, 중복 행이 한 번 생기면 findByOauthIdAndProvider 가 Optional 을 반환하므로
-- 그 계정은 이후 모든 로그인에서 NonUniqueResultException 으로 영구히 실패합니다.
--
-- 유지 정책: 중복 그룹마다 가장 오래된 계정(최소 id = 최초 가입 행)을 남기고,
-- 잃는 계정이 소유하던 데이터는 유지 계정으로 이관한 뒤 중복 행을 삭제합니다.
-- 사용자가 만든 러닝 아트를 잃지 않는 것이 목적입니다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 중복 매핑 산출
--
-- MySQL 은 UPDATE/DELETE 대상 테이블을 서브쿼리에서 직접 참조할 수 없으므로
-- (dup_id -> keep_id) 매핑을 별도 테이블로 먼저 물질화합니다.
-- 중복이 없으면 이 테이블은 비고, 이후 UPDATE/DELETE 는 모두 0행을 처리합니다.
-- ---------------------------------------------------------------------
CREATE TABLE tmp_user_dedup
(
    dup_id  BIGINT NOT NULL,
    keep_id BIGINT NOT NULL,
    PRIMARY KEY (dup_id)
) ENGINE = InnoDB;

INSERT INTO tmp_user_dedup (dup_id, keep_id)
SELECT u.id, k.keep_id
FROM users u
         JOIN (SELECT oauth_id,
                      oauth_provider,
                      MIN(id) AS keep_id
               FROM users
               GROUP BY oauth_id, oauth_provider
               HAVING COUNT(*) > 1) k
              ON u.oauth_id = k.oauth_id
                  AND u.oauth_provider = k.oauth_provider
WHERE u.id <> k.keep_id;

-- ---------------------------------------------------------------------
-- 2) 잃는 계정이 점유 중이던 작업 슬롯을 먼저 반납
--
-- ai_generation_tasks 에는 V2 가 만든 uk_ai_task_active_user(active_user_id 유니크)가
-- 걸려 있습니다. 두 계정이 각각 진행 중 작업을 갖고 있는 상태에서 user_id 만 유지 계정으로
-- 옮기면 active_user_id 가 겹쳐 이관 자체가 실패합니다.
-- 어차피 콜백을 받지 못한 채 방치된 작업이므로 이관 전에 FAILED 로 종결시키고
-- active_user_id 를 NULL 로 되돌려 충돌 가능성을 원천 제거합니다.
-- ---------------------------------------------------------------------
UPDATE ai_generation_tasks t
    JOIN tmp_user_dedup d ON t.user_id = d.dup_id
SET t.status         = 'FAILED',
    t.active_user_id = NULL,
    t.error_message  = '중복 계정 통합에 따른 진행 작업 종결 (V3 마이그레이션)',
    t.updated_at     = NOW(6)
WHERE t.status IN ('PENDING', 'PROCESSING');

-- ---------------------------------------------------------------------
-- 3) 자식 데이터 이관
--
-- V1 기준 users 를 참조하는 것은 running_arts(fk_running_arts_user)와
-- ai_generation_tasks(FK 없는 user_id) 둘뿐입니다.
-- running_arts 를 먼저 옮기지 않으면 4)의 DELETE 가 외래키 제약에 걸립니다.
-- ---------------------------------------------------------------------
UPDATE running_arts a
    JOIN tmp_user_dedup d ON a.user_id = d.dup_id
SET a.user_id = d.keep_id;

UPDATE ai_generation_tasks t
    JOIN tmp_user_dedup d ON t.user_id = d.dup_id
SET t.user_id = d.keep_id;

-- ---------------------------------------------------------------------
-- 4) 중복 users 행 제거
-- ---------------------------------------------------------------------
DELETE u
FROM users u
         JOIN tmp_user_dedup d ON u.id = d.dup_id;

DROP TABLE tmp_user_dedup;

-- ---------------------------------------------------------------------
-- 5) 유니크 인덱스 생성
--
-- 이름은 UserEntity 의 @UniqueConstraint(name = "uk_users_oauth") 와 일치시킵니다.
-- 이제 경합이 애플리케이션 방어를 뚫더라도 두 번째 INSERT 가 DB 에서 거부됩니다.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX uk_users_oauth
    ON users (oauth_id, oauth_provider);
