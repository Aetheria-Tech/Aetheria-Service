-- =====================================================================
-- V1: 기존 스키마 베이스라인
--
-- 이 스크립트는 Flyway 도입 시점의 스키마를 그대로 재현합니다.
-- 이미 테이블이 존재하는 기존 데이터베이스에서는 `baseline-on-migrate: true`,
-- `baseline-version: 1` 설정에 의해 '이미 적용됨'으로 간주되어 실행되지 않습니다.
-- 따라서 이 파일은 신규 환경이 빈 데이터베이스에서 부트스트랩할 때만 사용됩니다.
--
-- 컬럼 타입은 Hibernate가 실제로 생성했던 DDL을 그대로 옮긴 것입니다.
-- (@Enumerated(STRING)이 varchar가 아닌 MySQL ENUM으로 생성되는 점에 유의)
-- 임의로 바꾸면 `ddl-auto: validate`가 기동 시점에 실패합니다.
-- =====================================================================

CREATE TABLE users
(
    id                  BIGINT                      NOT NULL AUTO_INCREMENT,
    oauth_id            VARCHAR(255)                NOT NULL,
    oauth_provider      ENUM ('GOOGLE', 'KAKAO')    NOT NULL,
    email               VARCHAR(255)                NOT NULL,
    nickname            VARCHAR(255)                NOT NULL,
    role                ENUM ('ADMIN', 'USER')      NOT NULL,
    status_message      VARCHAR(255)                NULL,
    oauth_refresh_token TEXT                        NULL,
    created_at          DATETIME(6)                 NULL,
    updated_at          DATETIME(6)                 NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE running_arts
(
    id          BIGINT                                                          NOT NULL AUTO_INCREMENT,
    title       VARCHAR(255)                                                    NOT NULL,
    content     VARCHAR(255)                                                    NOT NULL,
    shape       VARCHAR(255)                                                    NOT NULL,
    proficiency ENUM ('BEGINNER', 'EXPERT', 'INTRODUCTION', 'MASTER', 'SKILLED') NOT NULL,
    gpx         LONGTEXT                                                        NULL,
    start_lat   DOUBLE                                                          NOT NULL,
    start_lon   DOUBLE                                                          NOT NULL,
    distance    DOUBLE                                                          NOT NULL,
    user_id     BIGINT                                                          NULL,
    created_at  DATETIME(6)                                                     NULL,
    updated_at  DATETIME(6)                                                     NULL,
    PRIMARY KEY (id),
    KEY idx_running_arts_user (user_id),
    CONSTRAINT fk_running_arts_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ai_generation_tasks
(
    id            VARCHAR(36)                                                     NOT NULL,
    user_id       BIGINT                                                          NOT NULL,
    shape         VARCHAR(255)                                                    NOT NULL,
    proficiency   ENUM ('BEGINNER', 'EXPERT', 'INTRODUCTION', 'MASTER', 'SKILLED') NOT NULL,
    status        ENUM ('COMPLETED', 'FAILED', 'PENDING', 'PROCESSING')           NOT NULL,
    input_s3_uri  VARCHAR(500)                                                    NULL,
    output_s3_uri VARCHAR(500)                                                    NULL,
    error_message VARCHAR(1000)                                                   NULL,
    result_art_id BIGINT                                                          NULL,
    created_at    DATETIME(6)                                                     NOT NULL,
    updated_at    DATETIME(6)                                                     NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
