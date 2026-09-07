-- =====================================================================
-- V5: (1) 자바 enum 에서 사라진 MASTER 등급을 DB 에서도 제거
--     (2) running_arts 의 FK·인덱스 이름을 V1 이 선언한 표준으로 수렴
--
-- 두 문제는 뿌리가 같습니다. V1 은 baseline-on-migrate 로 건너뛰어지므로,
-- 기존 DB 는 Flyway 도입 이전에 Hibernate ddl-auto: update 가 만들어 둔 상태
-- 그대로 남아 있습니다. 그래서 V1 이 "선언한" 스키마와 실제 스키마가 어긋납니다.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1) MASTER 등급 데이터 이동
--
-- Proficiency enum 에는 INTRODUCTION / BEGINNER / SKILLED / EXPERT 넷뿐인데
-- DB 에는 MASTER 행이 남아 있습니다. @Enumerated(STRING) 역변환이
-- "No enum constant Proficiency.MASTER" 로 터지므로, 그 행이 결과에 하나라도
-- 걸리면 목록·주변검색·상세조회가 전부 예외로 죽습니다.
--
-- MASTER 는 EXPERT 위의 최상위 등급이었으므로 살아있는 등급 중 가장 가까운
-- EXPERT 로 옮깁니다. 행을 지우지 않으므로 Redis GEO 인덱스 정리가 필요 없습니다.
--
-- ai_generation_tasks 는 현재 MASTER 행이 없지만 컬럼 정의가 동일하고
-- 다른 환경의 데이터는 확인할 수 없으므로 방어적으로 함께 돌립니다.
-- ---------------------------------------------------------------------
UPDATE running_arts
SET proficiency = 'EXPERT'
WHERE proficiency = 'MASTER';

UPDATE ai_generation_tasks
SET proficiency = 'EXPERT'
WHERE proficiency = 'MASTER';


-- ---------------------------------------------------------------------
-- 2) ENUM 정의에서 MASTER 제거
--
-- ⚠ ALGORITHM=COPY 를 반드시 명시해야 합니다.
-- MySQL 의 ENUM 은 값이 아니라 '순번'으로 저장됩니다. 현재 목록은
--   BEGINNER=1, EXPERT=2, INTRODUCTION=3, MASTER=4, SKILLED=5
-- 인데 가운데의 MASTER 를 빼면 SKILLED 가 5번에서 4번으로 밀립니다.
-- INPLACE 로 처리되어 순번이 그대로 재해석되면 SKILLED 행이 조용히 다른 값으로
-- 바뀔 수 있습니다. COPY 는 테이블을 재작성하며 문자열 값 기준으로 변환하므로
-- 이 사고를 막습니다.
--
-- (MySQL 8 은 값 삭제에 INPLACE 를 허용하지 않아 실질적으로 COPY 로 떨어지지만,
--  의도를 스크립트에 못 박아 둡니다.)
-- ---------------------------------------------------------------------
ALTER TABLE running_arts
    MODIFY COLUMN proficiency ENUM ('BEGINNER', 'EXPERT', 'INTRODUCTION', 'SKILLED') NOT NULL,
    ALGORITHM = COPY;

ALTER TABLE ai_generation_tasks
    MODIFY COLUMN proficiency ENUM ('BEGINNER', 'EXPERT', 'INTRODUCTION', 'SKILLED') NOT NULL,
    ALGORITHM = COPY;


-- ---------------------------------------------------------------------
-- 3) running_arts 의 FK·인덱스 이름 정규화
--
-- V1 은 CONSTRAINT fk_running_arts_user 와 KEY idx_running_arts_user 를 선언하지만,
-- 기존 DB 에는 Hibernate 가 만든 FK 제약(FK<해시>)과 InnoDB 가 그 이름으로 자동
-- 생성한 인덱스만 존재합니다. 즉 신규 환경과 기존 환경의 이름이 서로 다릅니다.
--
-- 같은 스크립트가 양쪽에서 돌아야 하므로 information_schema 를 읽어 조건부로
-- 실행합니다. 기존 이름은 환경마다 해시가 다르므로 절대 하드코딩하지 않고
-- 항상 조회해서 조립합니다. PREPARE 가 받아 주는 no-op 으로는 SELECT 1 을 씁니다
-- (DO 는 preparable 문이 아닙니다).
-- ---------------------------------------------------------------------

-- 3-1) 표준 이름 인덱스를 먼저 확보한다.
--      FK 가 기댈 인덱스가 남아 있어야 3-3 에서 낡은 인덱스를 지울 수 있습니다.
SET @has_idx := (SELECT COUNT(*)
                 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'running_arts'
                   AND INDEX_NAME = 'idx_running_arts_user');

SET @sql := IF(@has_idx = 0,
               'CREATE INDEX idx_running_arts_user ON running_arts (user_id)',
               'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3-2) FK 제약 이름을 표준으로 바꾼다.
--      MySQL 은 FK 이름 변경을 지원하지 않으므로 드롭 후 재생성해야 합니다.
SET @fk_name := (SELECT CONSTRAINT_NAME
                 FROM information_schema.TABLE_CONSTRAINTS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'running_arts'
                   AND CONSTRAINT_TYPE = 'FOREIGN KEY'
                 LIMIT 1);

SET @sql := IF(@fk_name IS NOT NULL AND @fk_name <> 'fk_running_arts_user',
               CONCAT('ALTER TABLE running_arts DROP FOREIGN KEY `', @fk_name, '`'),
               'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_fk := (SELECT COUNT(*)
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'running_arts'
                  AND CONSTRAINT_NAME = 'fk_running_arts_user');

SET @sql := IF(@has_fk = 0,
               'ALTER TABLE running_arts ADD CONSTRAINT fk_running_arts_user FOREIGN KEY (user_id) REFERENCES users (id)',
               'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3-3) 남은 낡은 인덱스를 지운다.
--      FK 를 드롭해도 그 이름으로 자동 생성됐던 인덱스는 그대로 남습니다.
SET @stale_idx := (SELECT DISTINCT INDEX_NAME
                   FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 'running_arts'
                     AND COLUMN_NAME = 'user_id'
                     AND INDEX_NAME <> 'idx_running_arts_user'
                   LIMIT 1);

SET @sql := IF(@stale_idx IS NOT NULL,
               CONCAT('DROP INDEX `', @stale_idx, '` ON running_arts'),
               'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
