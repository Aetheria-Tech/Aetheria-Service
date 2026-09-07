# 9. Hibernate가 만든 스키마와 Flyway가 선언한 스키마의 드리프트

> 요약 · [README — 9. Hibernate가 만든 스키마와 Flyway가 선언한 스키마의 드리프트](../../README.md#9-hibernate가-만든-스키마와-flyway가-선언한-스키마의-드리프트)
> 근거 · [`V5__drop_master_proficiency_and_normalize_art_keys.sql`](../../src/main/resources/db/migration/V5__drop_master_proficiency_and_normalize_art_keys.sql) · [`Proficiency.java`](../../src/main/java/com/serverbe/domain/model/art/vo/Proficiency.java) · [`RunningArtEntity.java`](../../src/main/java/com/serverbe/adapter/out/persistence/art/RunningArtEntity.java)

## 1. 문제의 뿌리

Flyway를 도입할 때 기존 DB에는 이미 테이블이 있었습니다. 그래서 `baseline-on-migrate: true`,
`baseline-version: 1`로 시작합니다. 이 설정의 의미는 **"기존 DB는 `V1`이 이미 적용된 것으로 치고
`V2`부터 실행한다"** 입니다.

```yaml
flyway:
  baseline-on-migrate: true   # 이미 테이블이 존재하는 기존 DB에서도 안전하게 시작
  baseline-version: 1         # 기존 DB는 V1을 '적용됨'으로 간주하고 V2부터 실행
```

여기서 조용한 균열이 생깁니다. 신규 환경은 `V1` 스크립트가 **실제로 실행되어** 스키마를 만들지만,
기존 환경의 스키마는 **`ddl-auto: update` 시절 Hibernate가 만들어 둔 상태 그대로**입니다.
`V1`이 "선언한" 것과 기존 환경의 "실물"이 미묘하게 다릅니다.

아래 두 문제는 증상이 전혀 달라 보이지만 뿌리가 같습니다.

---

## 9-1. 자바 enum에서 사라진 등급이 조회 API 전체를 죽이다

### 증상

목록 조회, 주변 검색, 상세 조회가 **전부** 예외로 죽었습니다. 그런데 항상 죽는 것이 아니라
**특정 데이터가 결과 집합에 걸릴 때만** 죽습니다.

```
IllegalArgumentException: No enum constant com.serverbe.domain.model.art.vo.Proficiency.MASTER
```

재현이 까다롭습니다. 개발 DB에서는 멀쩡한데 특정 환경에서만 터지고, **코드에는 아무 흔적도 남지
않습니다.** 최근 커밋을 아무리 봐도 원인이 없습니다.

### 원인

`Proficiency` enum에는 네 개뿐입니다.

```java
INTRODUCTION("입문자", 1, 5),
BEGINNER("초급자", 5, 10),
SKILLED("숙련자", 10, 15),
EXPERT("전문가", 15, 42);
```

그런데 DB에는 **과거에 존재했던 `MASTER` 행이 남아 있었습니다.** `@Enumerated(EnumType.STRING)`의
역변환이 실패하면 그 행 하나 때문에 **쿼리 전체**가 예외로 끝납니다. 한 행이 API 여러 개를 죽입니다.

### 검토한 대안

| 대안 | 기각 이유 |
| --- | --- |
| `MASTER`를 enum에 다시 추가 | 이미 없앤 등급을 도메인에 되살리는 것입니다. 화면·정책·거리 계산까지 전부 이 값을 다시 다뤄야 합니다. |
| `MASTER` 행을 삭제 | 사용자가 만든 러닝 아트를 지우는 것입니다. 되돌릴 수 없고, **Redis GEO 인덱스에서도 함께 지워야** 합니다. |
| `@Enumerated(ORDINAL)`로 전환 | 순번 저장은 더 취약합니다. enum 순서만 바뀌어도 전 데이터가 어긋납니다. |
| 커스텀 `AttributeConverter`로 미지의 값을 기본값 처리 | 데이터 파손을 코드로 덮는 것입니다. 어떤 행이 잘못됐는지 영영 모르게 됩니다. |

### 해결 — 데이터

행을 지우지 않고 **살아 있는 등급 중 가장 가까운 `EXPERT`로 옮겼습니다.** `MASTER`는 원래 `EXPERT` 위의
최상위 등급이었으므로 의미상 가장 가깝습니다.

```sql
UPDATE running_arts       SET proficiency = 'EXPERT' WHERE proficiency = 'MASTER';
UPDATE ai_generation_tasks SET proficiency = 'EXPERT' WHERE proficiency = 'MASTER';
```

삭제가 아니라 갱신이므로 **Redis GEO 인덱스를 따로 정리할 필요가 없습니다.** 좌표는 그대로이고
등급만 바뀝니다.

`ai_generation_tasks`에는 해당 행이 없었지만 컬럼 정의가 동일하고 다른 환경의 데이터는 확인할 수 없어
**방어적으로 함께 처리**했습니다.

### 해결 — 스키마 (`ALGORITHM = COPY`가 핵심)

```sql
ALTER TABLE running_arts
    MODIFY COLUMN proficiency ENUM ('BEGINNER', 'EXPERT', 'INTRODUCTION', 'SKILLED') NOT NULL,
    ALGORITHM = COPY;
```

**MySQL의 ENUM은 문자열이 아니라 순번으로 저장됩니다.** 현재 목록은 이렇습니다.

```
BEGINNER=1, EXPERT=2, INTRODUCTION=3, MASTER=4, SKILLED=5
```

가운데의 `MASTER`를 빼면 `SKILLED`가 **5번에서 4번으로 밀립니다.** `INPLACE`로 처리되어 저장된 순번이
그대로 재해석되면 **`SKILLED` 행이 조용히 다른 등급으로 바뀝니다.** 예외도, 로그도, 에러도 남지 않는
데이터 손상입니다. 나중에 발견해도 원래 값을 복원할 방법이 없습니다.

`COPY`는 테이블을 재작성하며 **문자열 값 기준으로** 변환하므로 이 사고를 막습니다.
MySQL 8은 값 삭제에 `INPLACE`를 허용하지 않아 실질적으로는 `COPY`로 떨어지지만,
**의도를 스크립트에 못 박아** 두었습니다. 나중에 이 스크립트를 읽는 사람이 "왜 COPY지?"라고 묻는 순간
이 함정을 알게 됩니다.

---

## 9-2. 환경마다 이름이 갈리는 FK와 인덱스

### 증상

증상이 없습니다. **다음 마이그레이션을 쓸 때 비로소 드러납니다.**

`V1`은 `fk_running_arts_user`와 `idx_running_arts_user`를 선언합니다. 하지만 기존 DB에는 Hibernate가
만든 **해시 이름 FK**(`FK<해시>`)와 InnoDB가 그 이름으로 자동 생성한 인덱스만 존재합니다.
즉 신규 환경과 기존 환경의 제약 이름이 서로 다릅니다.

이름이 갈리면 **이후 마이그레이션이 대상을 특정할 수 없습니다.** `ALTER TABLE ... DROP FOREIGN KEY
fk_running_arts_user`는 신규 환경에서는 성공하고 기존 환경에서는 실패합니다.

### 검토한 대안

| 대안 | 기각 이유 |
| --- | --- |
| 기존 이름을 하드코딩 | **해시가 환경마다 다릅니다.** 개발 DB에서 확인한 이름이 운영에서는 존재하지 않습니다. |
| 환경별로 다른 마이그레이션 스크립트 | Flyway 버전 이력이 환경마다 갈라집니다. 그 순간 "어느 환경이 어디까지 적용됐는가"를 사람이 추적해야 합니다. |
| 테이블을 새로 만들고 데이터 이관 | 운영 데이터를 옮기는 위험을 이름 정리 하나 때문에 감수할 이유가 없습니다. |
| 그냥 두고 앞으로 이름을 쓰지 않는다 | 문제를 미래로 미룰 뿐입니다. FK를 건드려야 하는 마이그레이션은 언제든 생깁니다. |

### 해결 — `information_schema`를 읽어 조건부 DDL 조립

하나의 스크립트가 양쪽 환경에서 모두 돌아야 하므로, **실행 시점에 실물을 조회해서** DDL을 조립합니다.

```sql
-- 3-1) 표준 이름 인덱스를 먼저 확보한다.
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
```

기존 이름은 **절대 하드코딩하지 않고 항상 조회해서 조립**합니다.

```sql
SET @fk_name := (SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'running_arts'
                   AND CONSTRAINT_TYPE = 'FOREIGN KEY' LIMIT 1);

SET @sql := IF(@fk_name IS NOT NULL AND @fk_name <> 'fk_running_arts_user',
               CONCAT('ALTER TABLE running_arts DROP FOREIGN KEY `', @fk_name, '`'),
               'SELECT 1');
```

**조건이 맞지 않을 때의 no-op으로 `SELECT 1`을 씁니다.** `DO`가 더 자연스러워 보이지만
**`DO`는 `PREPARE`가 받아 주는 문이 아닙니다.** 이런 사소한 제약이 스크립트를 실패시킵니다.

### 실행 순서에도 이유가 있다

| 단계 | 하는 일 | 왜 이 순서인가 |
| --- | --- | --- |
| 3-1 | 표준 이름 인덱스를 **먼저** 만든다 | FK가 기댈 인덱스가 남아 있어야 3-3에서 낡은 인덱스를 지울 수 있습니다. InnoDB는 FK를 뒷받침하는 인덱스가 없으면 삭제를 거부합니다. |
| 3-2 | FK를 **드롭 후 재생성** | **MySQL은 FK 제약의 이름 변경을 지원하지 않습니다.** `RENAME`이 없으니 지웠다 다시 만드는 수밖에 없습니다. |
| 3-3 | 남은 낡은 인덱스를 지운다 | FK를 드롭해도 **그 이름으로 자동 생성됐던 인덱스는 그대로 남습니다.** 별개의 객체입니다. |

### 재발 방지 — 엔티티에도 이름을 못 박는다

```java
@Table(name = "running_arts", indexes = {
        @Index(name = "idx_running_arts_user", columnList = "user_id")
})
...
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_running_arts_user"))
```

앞으로 만들어지는 스키마는 **처음부터 표준 이름을 갖습니다.**
드리프트는 한 번 정리하는 것보다 **다시 생기지 않게 막는 쪽**이 중요합니다.

---

## 검증

- **양쪽 환경에서 모두 도는지** — 이 스크립트의 핵심 요구사항입니다.
  - **신규 환경**: 빈 스키마에서 `V1`부터 순서대로 적용됩니다.

    ```bash
    docker compose down -v && docker compose up -d
    docker compose logs app | grep -E "Migrating schema|Successfully applied"
    ```
  - **기존 환경**: `ddl-auto: update` 시절 스키마를 흉내 낸 DB에 `baseline-on-migrate`로 적용해
    3-1~3-3 분기가 실제로 동작하는지 확인해야 합니다.
- **ENUM 정의 확인** — `MASTER`가 빠지고 나머지 네 값의 **순서가 유지**되는지 봅니다.

  ```bash
  docker compose exec mysql mysql -uroot -pletmein webflux \
    -e "SHOW COLUMNS FROM running_arts LIKE 'proficiency';"
  ```
- **데이터 손상 확인** — `SKILLED` 행 수가 마이그레이션 전후로 같아야 합니다. 이것이
  `ALGORITHM=COPY`가 지켜 낸 것입니다.

  ```bash
  docker compose exec mysql mysql -uroot -pletmein webflux \
    -e "SELECT proficiency, COUNT(*) FROM running_arts GROUP BY proficiency;"
  ```
- **이름 정규화 확인**

  ```bash
  docker compose exec mysql mysql -uroot -pletmein webflux -e "
    SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
     WHERE TABLE_NAME='running_arts' AND CONSTRAINT_TYPE='FOREIGN KEY';
    SHOW INDEX FROM running_arts;"
  ```
- **엔티티-스키마 일치** — `ServerBeApplicationTests`가 컨텍스트를 띄우며 Hibernate `validate`를 수행합니다.

  ```bash
  ./gradlew integrationTest --tests "com.serverbe.ServerBeApplicationTests"
  ```

## 남은 과제

- `ddl-auto: validate`는 **테이블·컬럼·타입만** 검증합니다. 유니크 인덱스, FK 이름, 일반 인덱스는
  보지 않습니다. 즉 이 문서가 다룬 종류의 드리프트는 **기동 시점에 여전히 드러나지 않습니다.**
  ([7. 소셜 계정 중복](07-oauth-duplicate-account.md)이 같은 사각지대에서 나온 사고입니다.)
  `information_schema`를 읽어 기대 스키마와 대조하는 통합 테스트가 있으면 이 계열의 사고를 막을 수 있습니다.
- 기존 환경을 재현한 마이그레이션 테스트가 없습니다. 지금은 조건부 DDL의 "기존 환경" 분기가
  실제로 검증된 적이 없고, 운영에 적용해 본 것이 유일한 근거입니다.
