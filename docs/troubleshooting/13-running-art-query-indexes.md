# 13. 러닝 아트 조회 인덱스 — filesort와 뚱뚱한 풀스캔

> 요약 · 인덱스 둘을 추가해 목록 조회를 **21ms → 0.05ms**, GEO 웜업을 **482ms → 75ms** 로 줄였습니다
> 근거 · [`V6__add_running_art_query_indexes.sql`](../../src/main/resources/db/migration/V6__add_running_art_query_indexes.sql) · [`JpaRunningArtRepository.java`](../../src/main/java/com/serverbe/adapter/out/persistence/art/JpaRunningArtRepository.java) · [`RunningArtPersistenceAdapter.java`](../../src/main/java/com/serverbe/adapter/out/persistence/art/RunningArtPersistenceAdapter.java)
> **자바 코드와 API 응답은 한 줄도 바뀌지 않았습니다.** 산출물은 마이그레이션 하나입니다.

## 1. 상황

`running_arts`는 `V1` 이후 인덱스가 **`idx_running_arts_user(user_id)` 하나뿐**이었습니다.
그런데 이 테이블을 읽는 쿼리 둘은 그 하나로 좁혀지지 않습니다.

**A — 내 러닝 아트 목록.** 목록 API가 `?sort=createdAt,desc`를 받으므로 실제로 나가는 쿼리는
이렇습니다.

```sql
SELECT * FROM running_arts WHERE user_id = ? ORDER BY created_at DESC LIMIT 20 OFFSET ?
```

**B — Redis GEO 웜업.** 기동 시 좌표를 Redis GEO로 밀어 넣습니다. 쓰는 컬럼은 셋뿐입니다.

```sql
SELECT id, start_lat, start_lon FROM running_arts
```

## 2. 근거 — `EXPLAIN`이 둘 다 지목합니다

```bash
docker compose exec mysql mysql -uroot -pletmein webflux -e "
  EXPLAIN SELECT * FROM running_arts
   WHERE user_id = 1 ORDER BY created_at DESC LIMIT 20\G"
```

```
type: ref              key: idx_running_arts_user
rows: 35380            Extra: Using filesort        ← A
```

```
type: ALL              key: NULL
rows: 471396           Extra: NULL                  ← B
```

**A의 `Using filesort`가 말하는 것** — 인덱스는 `user_id`까지만 좁혀 주고 정렬은 못 합니다.
그래서 그 사용자의 아트를 **전부 읽어 메모리에서 정렬한 뒤 20건을 잘라 냅니다.**
20건을 원했는데 35,380건을 만지는 셈이고, **아트가 늘수록 정렬 대상이 함께 늘어납니다.**

**B의 `type: ALL`이 말하는 것** — 쓸 수 있는 인덱스가 없어 클러스터드 인덱스를 통째로 훑습니다.
여기서 진짜 비용은 건수가 아니라 **행의 크기**입니다.

| 인덱스 | 크기 |
| --- | --- |
| `PRIMARY` (클러스터드 = 행 전체) | **562MB** |
| `idx_running_arts_location` (신규, 커버링) | **18MB** |

```bash
docker compose exec mysql mysql -uroot -pletmein -e "
  SELECT index_name, ROUND(stat_value*16/1024) mb FROM mysql.innodb_index_stats
   WHERE database_name='webflux' AND table_name='running_arts' AND stat_name='size';"
```

**31배 차이의 정체는 `gpx LONGTEXT`입니다.** 웜업은 좌표 셋만 쓰는데, 클러스터드 인덱스를
훑으면 **경로 데이터까지 전부 디스크에서 읽습니다.**

## 3. 해결 — 인덱스 둘

```sql
CREATE INDEX idx_running_arts_user_created ON running_arts (user_id, created_at DESC);
CREATE INDEX idx_running_arts_location     ON running_arts (start_lat, start_lon);
```

**컬럼 순서의 근거는 [08번](08-scheduler-full-scan-and-write-amplification.md)과 같습니다** —
등치 조건이 선두, 범위·정렬 조건이 뒤여야 인덱스가 이어서 일합니다. 08번은
`(status, updated_at)`을 그 이유로 택했고, 여기서는 `(user_id, created_at)`입니다.

**두 번째 인덱스에 `id`를 넣지 않은 것이 핵심입니다.** InnoDB의 보조 인덱스는 **PK를 암묵적으로
포함**하므로, `(start_lat, start_lon)` 둘만 잡아도 `id`까지 인덱스 안에서 해결됩니다.
그래서 `Extra`에 `Using index`(커버링)가 뜨고, 562MB가 아니라 18MB만 훑습니다.

## 4. 측정

### 측정 환경 — 운영 수치가 아닙니다

로컬 도커 MySQL 8.0(`docker-compose.yml`), Windows 10 / Docker Desktop. 시드 데이터는
`gpx`에 **855바이트**를 채워 실제 경로 데이터와 비슷한 행 크기를 만들었습니다 — 이 컬럼을
비워 두면 B의 효과가 통째로 사라져 측정이 거짓말을 합니다.

| 규모 | `running_arts` | `users` | 헤비 유저의 아트 | 테이블 |
| --- | --- | --- | --- | --- |
| 소 | 5만 | 5천 | 2,009 | — |
| 대 | 51.8만 | 5만 | 20,018 | 데이터 523MB |

`EXPLAIN ANALYZE`의 최상위 `actual time`을 **7회 측정한 중앙값**입니다. 1회 측정은 노이즈입니다.
**찬 상태**는 MySQL을 재시작해 버퍼 풀을 비운 뒤의 첫 실행입니다 — 인덱스 효과는 디스크 읽기가
줄어드는 데서 크게 나오므로, 데운 값만 실으면 효과가 과소평가됩니다.

### 결과

| 쿼리 | 규모 | 상태 | 전 | 후 | 배수 |
| --- | --- | --- | ---: | ---: | ---: |
| **A** 목록 첫 페이지 | 소 | 데움 | 2.07ms | **0.043ms** | 48× |
| **A** 목록 첫 페이지 | 대 | 데움 | 21.0ms | **0.053ms** | 394× |
| **A** 목록 첫 페이지 | 대 | 참 | 32.1ms | **0.071ms** | 455× |
| **A** 깊은 페이지 | 소 | 데움 | 2.82ms | **1.03ms** | 2.7× |
| **A** 깊은 페이지 | 대 | 데움 | 28.5ms | **9.29ms** | 3.1× |
| **B** GEO 웜업 | 소 | 데움 | 11.1ms | **7.37ms** | 1.5× |
| **B** GEO 웜업 | 대 | 데움 | 482ms | **74.6ms** | 6.5× |
| **B** GEO 웜업 | 대 | 참 | 607ms | **79.2ms** | 7.7× |

**두 규모로 잰 이유가 여기서 드러납니다.** 숫자 하나였다면 "빨라졌다"까지만 말했겠지만,
두 점은 **기울기**를 보여 줍니다.

- **A는 규모에 따라 개선 폭이 커집니다** (48× → 394×). 최적화 전 시간이 사용자의 아트 수에
  비례해 늘어나는데(2.07ms → 21ms), 후에는 거의 변하지 않기 때문입니다(0.043ms → 0.053ms).
  **정렬을 없애면 20건을 위해 20건만 읽습니다.**
- **A의 깊은 페이지는 3배에 그칩니다.** `OFFSET`은 인덱스로 건너뛸 수 없어 여전히 읽고 버립니다.
  **인덱스는 정렬을 없앴을 뿐 offset 페이징의 본질적 비용을 없애지 못합니다** — 7절 참고.
- **B는 규모가 커져야 의미가 생깁니다** (1.5× → 6.5×). 소 규모에서는 테이블 자체가 작아
  풀스캔이 싸기 때문입니다.

## 5. 무엇을 내주었나

인덱스는 공짜가 아니므로 쓰기 비용을 같이 쟀습니다.

| 항목 | 인덱스 없음 | 인덱스 둘 |
| --- | ---: | ---: |
| 2만 건 `INSERT` | 0.655초 | 0.769초 (**+17%**) |
| 인덱스 총 크기 | 22MB | 66MB (**+44MB**) |

읽기를 6~400배 줄이는 대가로 **쓰기가 17% 느려지고 디스크가 44MB 늘었습니다.** 이 서비스에서
러닝 아트는 **한 번 쓰고 여러 번 읽는** 데이터라 이 교환은 유리합니다.

`V6` 적용 자체는 51.8만 건 테이블에서 **3.58초** 걸렸습니다. MySQL 8.0의 온라인 DDL이라
인덱스 생성 중에도 읽기·쓰기가 막히지 않습니다.

## 6. 검증

- **실행 계획이 바뀌었는지** — 이 항목의 핵심 검증입니다.
  **계획이 그대로인데 시간만 좋아졌다면 그건 캐시 효과이지 최적화가 아닙니다.**

  ```bash
  docker compose exec mysql mysql -uroot -pletmein webflux -e "
    EXPLAIN SELECT * FROM running_arts
     WHERE user_id = 1 ORDER BY created_at DESC LIMIT 20\G"
  ```

  | | 전 | 후 |
  | --- | --- | --- |
  | A | `key: idx_running_arts_user` / `Extra: Using filesort` | `key: idx_running_arts_user_created` / `Extra: NULL` |
  | B | `type: ALL` / `key: NULL` | `type: index` / `key: idx_running_arts_location` / `Extra: Using index` |

- **Flyway 적용** — 51.8만 건이 든 DB에 앱을 띄워 확인했습니다.
  `Migrating schema webflux to version "6 - add running art query indexes"` →
  `Successfully applied 1 migration ... (execution time 00:03.580s)`, 이어서
  `Started ServerBeApplication`.
- **인덱스 존재 확인**

  ```bash
  docker compose exec mysql mysql -uroot -pletmein webflux -e "SHOW INDEX FROM running_arts;"
  ```

- **기존 테스트** — `gradlew test` 전체 초록, `gradlew integrationTest`의 `contextLoads` 초록.

## 7. 검토했다 기각한 대안

**주변 아트 조회에서 `gpx`를 빼는 것.** `RunningArtSearchService`는 Redis GEO가 준 ID로
`findAllByIdIn`을 호출하는데, 이때 `gpx LONGTEXT`까지 전부 로딩합니다. 목록 응답에서 경로
데이터를 빼면 B와 같은 이유로 크게 빨라집니다.

**하지만 `RunningArtResult`가 실제로 `gpx`를 반환합니다.** 빼면 빨라지는 대신 **API 응답이
바뀝니다.** 이 항목은 "동작을 바꾸지 않고 빠르게 한다"가 전제였으므로 범위 밖입니다.
클라이언트가 목록에서 경로를 그리지 않는다는 것이 확인되면 그때 별도 항목으로 엽니다.

**기존 `idx_running_arts_user(user_id)`를 지우는 것.** 새 복합 인덱스가 선두 컬럼으로
`user_id`를 가지므로 조회 목적으로는 대체됩니다. 그런데 [`V5`](../../src/main/resources/db/migration/V5__drop_master_proficiency_and_normalize_art_keys.sql)가
이 인덱스를 `fk_running_arts_user` 외래 키와 함께 다루고 있고, **FK는 인덱스를 요구하므로 먼저
지우면 제약이 인덱스를 잃습니다.** 옮기는 작업이 선행되어야 하고 그건 조회 최적화와 다른
판단이라 남겼습니다. 22MB의 중복 비용을 알고 남긴 것입니다.

## 8. 남은 과제

- **`OFFSET` 페이징은 그대로입니다.** 깊은 페이지가 3배밖에 못 줄어든 이유이고, 근본 해법은
  커서 기반 페이징(`WHERE created_at < ? ORDER BY created_at DESC LIMIT 20`)입니다.
  다만 그건 **API 계약을 바꾸는 일**이라 이 항목에 넣지 않았습니다.
- **`findAllLocations()`의 `WHERE start_lat IS NOT NULL AND start_lon IS NOT NULL`은 죽은
  조건입니다.** 두 컬럼은 `V1`부터 `NOT NULL`이라 아무것도 거르지 못합니다. 방어적으로 읽히지만
  실제로는 **없는 방어**이고, 이번에는 인덱스만 다루기로 해서 기록만 남깁니다.
- **운영에서 측정된 적은 없습니다.** 위 수치는 전부 로컬 도커 MySQL의 시드 데이터 기준입니다.
  08번이 같은 문장을 남겼는데, 이번에는 최소한 **어떤 조건에서 잰 숫자인지**는 적어 두었습니다.
