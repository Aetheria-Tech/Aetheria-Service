-- =====================================================================
-- V6: running_arts 조회 인덱스 둘 추가
--
-- 이 테이블은 V1 이후 인덱스가 idx_running_arts_user(user_id) 하나뿐이었습니다.
-- 그 하나로는 아래 두 쿼리를 좁히지 못합니다.
--
-- 1) 내 러닝아트 목록 — JpaRunningArtRepository.findByUser_Id(userId, pageable)
--
--      WHERE user_id = ? ORDER BY created_at DESC LIMIT ?, ?
--
--    목록 API 가 ?sort=createdAt,desc 를 받으므로 정렬 컬럼이 조건에 늘 붙습니다.
--    기존 인덱스는 user_id 까지만 좁혀 주고 정렬은 못 하므로, EXPLAIN 의 Extra 가
--    "Using filesort" 로 나옵니다. 즉 그 사용자의 아트 전부를 읽어 메모리에서 정렬한 뒤
--    20건을 잘라 냅니다. 아트가 늘수록 정렬 대상이 함께 늘어나는 구조입니다.
--
-- 2) Redis GEO 웜업 — RunningArtPersistenceAdapter.findAllLocations()
--
--      SELECT id, start_lat, start_lon FROM running_arts
--
--    쓰는 컬럼은 셋뿐인데 이 셋을 담은 인덱스가 없어 클러스터드 인덱스를 풀스캔합니다
--    (type: ALL). 문제는 건수가 아니라 행의 크기입니다 — 이 테이블의 행에는
--    gpx LONGTEXT 가 들어 있어, 쓰지도 않는 경로 데이터까지 전부 디스크에서 읽습니다.
--
-- 컬럼 순서의 근거는 V4 와 같습니다. 등치 조건이 선두, 범위·정렬 조건이 뒤여야
-- 인덱스가 이어서 일합니다. 반대로 잡으면 선두 컬럼에서 탐색이 끊깁니다.
-- =====================================================================

-- 1) 목록 조회용 복합 인덱스
--    user_id 로 좁힌 뒤 created_at 순서가 인덱스에 이미 들어 있으므로 정렬이 사라집니다.
--    DESC 를 명시하는 이유는 MySQL 8.0 이 내림차순 인덱스를 실제로 지원하기 때문입니다.
--    (5.7 까지는 파싱만 하고 무시했습니다.)
CREATE INDEX idx_running_arts_user_created
    ON running_arts (user_id, created_at DESC);

-- 2) GEO 웜업용 커버링 인덱스
--    InnoDB 의 보조 인덱스는 PK 를 암묵적으로 포함하므로, 이 둘만 잡아도
--    id·start_lat·start_lon 세 컬럼이 모두 인덱스 안에서 해결됩니다(Using index).
--    id 를 명시적으로 나열할 필요가 없습니다.
CREATE INDEX idx_running_arts_location
    ON running_arts (start_lat, start_lon);

-- 기존 idx_running_arts_user(user_id) 는 남깁니다.
-- 새 복합 인덱스가 선두 컬럼으로 user_id 를 가져 조회 목적으로는 대체되지만,
-- V5 가 이 인덱스를 fk_running_arts_user 외래 키와 함께 다루고 있습니다.
-- FK 는 인덱스를 요구하므로 먼저 지우면 제약이 인덱스를 잃습니다.
-- 지우려면 FK 가 새 인덱스를 쓰도록 옮기는 작업이 선행되어야 하고,
-- 그건 이 마이그레이션의 목적(조회 최적화)과 다른 판단입니다.
