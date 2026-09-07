# `refactor/61` 프로덕션 투입 검토

> 판정 · **올려도 됩니다. 차단 사유 없음.**
> 기준 브랜치 · `develop` | 대상 · `refactor/61` (리팩터링 커밋 여섯)
> 성격 · 검증 기록 | 세 축(`architecture/`·`troubleshooting/`·`refactor/`) 어디에도 속하지 않는 단독 보고서입니다.

## 1. 무엇을 검토했나

`refactor/61`은 [리팩터링 백로그](refactor/README.md) 12개 항목을 닫은 브랜치입니다.
`develop` 위에 여섯 커밋이 얹혀 있습니다.

| 커밋 | 닫은 항목 |
| --- | --- |
| 아웃바운드 어댑터 및 코드 정리, 주석·트랜잭션 오류 수정 | 01~04 |
| 웹 관심사를 인프라에서 웹 어댑터로 이동 | 05, 06 |
| 서비스 응집도 강화 및 계층 경계 확립 | 07, 08 |
| `TokenPersistencePort`와 구현체를 두 책임으로 분할 | 09, 11 |
| 어댑터 테스트 공백 채우고 반응형 포트 규칙 고정 | 10, 11, 12 |
| 휘발성 커밋 해시 제거 및 지침 업데이트 | 문서만 |

- 프로덕션 코드 **54파일 / +1033 −845**
- 테스트 **26파일 / +2615 −163**
- `application.yml`과 Flyway 마이그레이션은 **한 줄도 바뀌지 않았습니다**

**브랜치의 성격이 검토 기준을 정했습니다 — 전부 리팩터링이고 새 기능이 없습니다.**
그래서 물어야 할 것은 "기능이 맞게 도는가"가 아니라 **"동작이 바뀌지 않았는가"** 였습니다.

리팩터링이 프로덕션을 깨뜨리는 경로는 대개 셋인데, **셋 다 컴파일러도 단위 테스트도 보지
않습니다** — 빈 배선, 외부에 노출된 계약(HTTP 응답·Redis 키), 트랜잭션 경계.
그래서 단위 테스트 295개가 초록인 것과 별개로 아래를 따로 확인했습니다.

## 2. 가장 큰 배포 위험이었던 것 — Redis 키 호환성

`TokenPersistenceAdapter` 하나가 `RefreshTokenSessionAdapter` + `TokenBlacklistAdapter`로
갈라지면서 키 조립이 `TokenRedisKeys`로 빠졌습니다. **키 문자열이 한 글자라도 달라지면
배포 순간 살아 있던 세션이 전부 조회 불가가 되고, 더 나쁘게는 무효화해 둔 토큰이
블랙리스트에서 보이지 않게 됩니다.**

`develop`의 구 어댑터와 문자 단위로 대조한 결과 **다섯 모양이 모두 동일**합니다.

| 키 | 모양 | 결과 |
| --- | --- | --- |
| 기기별 토큰 | `%s:%d:%s:%s` | 동일 |
| 세션 인덱스 | `%s:%s:%s` | 동일 |
| 토큰 접두사(스크립트 재조립용) | `%s:%d:%s:` | 동일 |
| AT 블랙리스트 | `%s:%s` + `sha256Hex` | 동일 |
| RT 블랙리스트 | `%s:%s` + `sha256Hex` | 동일 |

프로퍼티 출처(`redisProperties.auth().prefix()` 등)도 같고, 저장 값 `"logout"`·`"used"`도
그대로입니다. **Lua 스크립트 파일 자체는 이 브랜치에서 바뀌지 않았으므로 남은 변수는
호출 쪽이 넘기는 값뿐이었는데**, 네 스크립트의 KEYS 순서와 ARGV가 전부 일치합니다 —
`rotate_token.lua`의 `KEYS[3]`(구 토큰 블랙리스트 키)과 `ARGV[8]`(기본 TTL 5분)까지.

> **키 조립을 한 클래스로 모은 것이 이 검토를 쉽게 만들었습니다.** 두 어댑터가 각자
> 조립했다면 대조할 자리가 두 곳이었고, 한쪽만 어긋나는 경우를 따로 봐야 했습니다.

## 3. 실제로 띄워서 확인한 것

**빈 배선은 기동해 봐야 압니다.** 이 브랜치는 토큰 어댑터를 1개에서 2개로 나누고, Lua
스크립트 `@Qualifier` 주입을 다른 클래스로 옮기고, `OAuthClientConfig`에
`Map<OAuthProvider, OAuthClientPort>` 조립을 새로 넣었습니다.

컨텍스트 기동 테스트(`ServerBeApplicationTests`)는 `@Tag("integration")`이라 기본
`gradlew test`에서 빠집니다 — **즉 그동안의 초록은 기동을 증명하지 않았습니다.**

`docker compose up -d --build`로 **ECS에 올라갈 이미지와 같은 이미지**를 띄웠습니다.

```
Started ServerBeApplication in 11.275 seconds
```

- 오류·경고 로그 없음
- `actuator/health` **UP** — db·redis·서킷브레이커 5종 전부 UP, Flyway 정상
- `gradlew integrationTest`의 `contextLoads`도 별도로 초록

배선 셋을 각각 확인했습니다.

- **`@Qualifier` 넷이 `LuaScriptConfig`의 빈 이름과 1:1로 맞습니다.** 이름이 틀리면 기동이
  실패하지만 **서로 바뀌어 붙으면 기동은 되고 동작만 틀리므로**, 기동 성공만으로는
  부족해 이름을 직접 대조했습니다.
- OAuth 어댑터 둘이 `GOOGLE`·`KAKAO`로 겹치지 않아 조회표가 정상 조립됩니다.
- 패키지를 옮긴 `BusinessExceptionHandler`(`@RestControllerAdvice`)가 새 자리에서도
  컴포넌트 스캔에 잡힙니다.

### HTTP 계약도 실제 응답으로 확인했습니다

```
GET /api/v1/users/me  (인증 없음)
HTTP 401
{"success":false,"httpStatus":"UNAUTHORIZED","error":{"code":"AUTH_201","message":"인증이 필요합니다."}}
```

`RestApiResponse`·`BusinessExceptionHandler`·`ErrorKindHttpStatusMapper`가
`infrastructure`에서 `adapter.in.web`으로 옮겨 갔지만, **diff에 패키지 줄과 javadoc 외에는
아무것도 없습니다.** `ErrorKind` → `HttpStatus` 매핑표는 한 줄도 바뀌지 않았습니다.

## 4. 대조로 확인한 것

- **트랜잭션 경계 보존.** `RunningArtService`의 다섯 메서드가 `readOnly` 여부까지 그대로이고,
  `registerFromPolyline`은 `@Transactional`을 달고 새 서비스로 옮겨 갔습니다.
  `getTaskStatus`는 `develop`에서도 트랜잭션이 없었고 지금도 없으며, **본문이 문자 그대로
  옮겨졌습니다.**
- **`WithdrawService`의 `@Transactional` 제거가 안전합니다.** 실제 경계는
  `UserDataCleanupManager.deleteAllUserData`에 있고 그쪽 애노테이션은 그대로입니다.
  제거된 것은 `Mono` 위에서 **애초에 아무 일도 하지 않던** 애노테이션입니다.
- **S3 보상 흐름 무변경.** `AiGenerationService`·`AiResultRetrievalService`·
  `AiTaskResourceCleaner`의 실질 코드 변화는 `getTaskStatus` 추출뿐입니다
  ([2번 트러블슈팅](troubleshooting/02-s3-orphan-saga-compensation.md)이 기록한 흐름).
- **JWT 어댑터는 javadoc 한 곳만 바뀌었습니다.** `JwtTokenProvider`·`JwtTokenResolver`·
  `JwtKeyManager`·`AesGcmEncryptor` 로직 변경 0줄.
- **죽은 코드 삭제에 잔존 참조 없음**, 새로 들어온 TODO/FIXME 없음.
- **`AiTestController`는 `@Profile({"local","dev"})`라 프로덕션에 뜨지 않습니다.**
  `SecurityConfig`에도 테스트 경로 허용이 없습니다.

## 5. 배포 시 주의 — 하나

**무중단 배포 중 구/신 버전이 동시에 도는 구간이 안전합니다.** Redis 키·Lua 인자·HTTP 응답
봉투가 모두 같으므로 두 버전이 같은 Redis와 DB를 공유해도 서로의 데이터를 읽고 씁니다.
`application.yml`과 마이그레이션이 바뀌지 않아 **롤백도 이전 이미지 재배포로 끝납니다.**

## 6. 후속 과제 (차단 아님)

- **`Jwts.builder()`·`SignatureAlgorithm`이 deprecated API입니다**(`JwtTokenProvider`).
  지금 올리는 데는 문제가 없고, **이제 JWT 테스트 19개가 그물로 깔려 있어** 올릴 준비가
  되어 있습니다.
- **`infrastructure` → `adapter` 역방향 의존이 8곳 있습니다.** `WebConfig`의 리졸버 넷,
  `SecurityConfig`의 필터, `RedisPubSubConfig`의 리스너, 시큐리티 훅 둘 — 전부 "인프라가
  어댑터를 조립한다"는 같은 성격입니다. 동작 문제는 아니지만
  [05번](refactor/05-web-concerns-in-infrastructure.md)이 스스로 **"막는 규칙이 없어 조용히
  통과한다"** 고 적어 둔 경계이고, 규칙으로 만들려면 먼저 그 둘의 자리를 정해야 합니다.
- 도달 불가 catch 둘(`JwtTokenResolver`)은
  [12번](refactor/12-test-gaps-outbound-adapters.md)이 기록해 둔 그대로 남아 있습니다.

## 7. 검증하지 못한 것

**"이상 없음"이 아니라 "확인하지 않았음"입니다.**

- **Lua 스크립트의 원자성.** 목은 스크립트에 넘긴 인자까지만 봅니다. 다만 이 브랜치는
  스크립트 파일을 건드리지 않았고 호출 인자가 `develop`과 동일하므로, **검증되지 않은
  부분의 동작이 바뀌지 않았다는 것은 확실합니다.**
- **Querydsl 쿼리의 실제 실행.** `JPAQueryFactory`는 생성자 의존으로만 목이고 fluent 체인을
  흉내 내지 않습니다 — [11번](refactor/11-test-gaps-persistence-adapters.md)의 기록대로이고,
  흉내 내면 테스트가 프로덕션 코드의 호출 순서를 베낀 것이 되어 쿼리가 틀려도 초록입니다.
- **로그인·재발급·탈퇴의 엔드투엔드 시나리오.** OAuth 자격증명이 필요해 돌리지 않았습니다.
  기동·401 응답 봉투·health까지만 확인했습니다.

## 8. 결론

**리팩터링이 동작을 바꾸지 않았다는 근거가 세 겹입니다.**

1. 단위 테스트 **295개 초록** (아키텍처 규칙 13개 + 어댑터 커버리지 규칙 포함)
2. **프로덕션 동등 이미지에서 정상 기동** — 배선이 실제로 맞음
3. **외부 계약의 `develop` 대조** — Redis 키·Lua 인자·HTTP 매핑·트랜잭션 경계

세 번째가 없으면 1·2는 "지금 이 버전 안에서는 일관되다"까지만 말합니다. **배포는 구 버전이
남긴 데이터 위에서 일어나므로**, 이 브랜치처럼 저장소 키와 응답 봉투를 건드린 리팩터링은
대조가 판단의 핵심이었습니다.
