# 11. 설계 기록 — 항목으로 세우지 않은 판단들

> 요약 · [README — 그 외 설계 기록](../../README.md#그-외-설계-기록)

트러블슈팅 1~10번만큼 하나의 장애로 떨어지지는 않지만, **왜 다른 선택이 아니었는지**를 남겨 둘 가치가
있는 판단들입니다. 여러 항목이 서로를 참조하므로 관련 문서 링크를 함께 답니다.

---

## 1. 트랜잭션 커밋 이후에 Redis를 반영한다

> [`RunningArtService.java`](../../src/main/java/com/serverbe/application/service/RunningArtService.java)

러닝 아트를 삭제할 때 DB 삭제와 Redis GEO 삭제를 **같은 자리에서** 수행하면, DB 트랜잭션이 롤백되어도
Redis 데이터는 이미 사라진 뒤입니다. 러닝 아트는 살아 있는데 **주변 검색에서만 보이지 않는** 상태가 됩니다.

`TransactionSynchronization#afterCommit`으로 커밋 성공 이후에만 GEO를 갱신하도록 분리했습니다.

**왜 반대 순서(Redis 먼저)는 안 되나** — Redis를 먼저 지우고 DB를 지우면 실패 구간이 그대로 남습니다.
어느 쪽을 먼저 하든 "둘 중 하나만 성공"이 가능하다면, **되돌릴 수 있는 쪽을 나중에** 해야 합니다.
DB는 롤백되지만 Redis 삭제는 되돌릴 수 없으므로 Redis가 뒤입니다.

**왜 2PC가 아닌가** — Redis는 XA 참여자가 아니고, GEO 인덱스는 **DB에서 재구성 가능한 파생 데이터**입니다.
실제로 기동 시 `RedisGeoWarmUpListener`가 MySQL을 읽어 GEO를 다시 채웁니다. 최종 일관성으로 충분합니다.

같은 패턴이 [3. SQS 콜백 경합 §5-4](03-sqs-callback-race-condition.md#5-4-정리는-반드시-커밋-이후에)와
[8. 스케줄러 비용 §6-4](08-scheduler-full-scan-and-write-amplification.md#6-4-정리와-알림은-커밋-이후에)에도
나옵니다. **외부 시스템에 대한 되돌릴 수 없는 행위는 커밋 이후**라는 하나의 규칙입니다.

---

## 2. 좀비 태스크 실패 알림도 커밋 이후에

> [`AiTaskCleanupService.java`](../../src/main/java/com/serverbe/application/service/AiTaskCleanupService.java)

타임아웃 정리 스케줄러는 원래 상태를 `FAILED`로 바꾸기만 하고 **알림을 보내지 않았습니다.**
이미 `SseEmitter`를 열고 결과를 기다리던 클라이언트는 아무 이벤트도 받지 못한 채
**자신의 SSE 타임아웃(10분)까지 무한 로딩**에 머물렀습니다.

서버 입장에서는 "정리 완료"인데 사용자 화면은 여전히 돌고 있습니다. 로그만 보면 정상입니다.

S3 임시 자원 정리와 **같은 `afterCommit` 블록**에 실패 알림을 묶었습니다. 커밋 이후여야 하는 이유는
1번과 같습니다 — 상태 갱신이 롤백됐는데 클라이언트만 실패 알림을 받으면 **SSE 연결이 터미널 상태로
닫혀** 되돌릴 수 없습니다.

반대로 **알림 발송 실패는 로그만 남기고 삼킵니다.** 상태는 이미 커밋되어 되돌릴 수 없고,
한 건의 알림 실패가 나머지 태스크의 마무리까지 중단시켜서는 안 되기 때문입니다.
방향이 비대칭인 것이 요점입니다 — **커밋 전 실패는 전파하고, 커밋 후 실패는 삼킨다.**

---

## 3. 서킷 브레이커 오작동 방지 — 4xx와 5xx를 나눈다

> [`application.yml`](../../src/main/resources/application.yml)

외부 API의 **4xx는 우리 요청이 잘못된 것**이고 **5xx는 상대 서버 장애**입니다.
서킷 브레이커가 막아야 하는 것은 후자뿐입니다.

이를 구분하지 않으면, 사용자가 **존재하지 않는 주소를 반복 입력**하는 것만으로 지오코딩 회로가 열립니다.
카카오 API는 멀쩡한데 우리가 스스로 차단하는 셈이고, 그동안 **정상 주소를 입력한 다른 사용자까지**
지오코딩을 쓰지 못합니다.

예외를 `ExternalApiClientException`(4xx) / `ExternalApiException`(5xx)으로 분리하고 4xx를
`ignoreExceptions`에 등록했습니다.

```yaml
recordExceptions:
  - java.util.concurrent.TimeoutException
  - java.io.IOException
  - org.springframework.web.reactive.function.client.WebClientRequestException
  - com.serverbe.domain.exception.external.ExternalApiException   # 5xx 에러
ignoreExceptions:
  - com.serverbe.domain.exception.external.ExternalApiClientException  # 4xx 는 실패율에 포함하지 않음
```

`slowCallRateThreshold`도 함께 설정했습니다. **응답이 느린 것은 실패가 아니지만 스레드 고갈을 부릅니다.**
에러율만 보는 서킷 브레이커는 "전부 200 OK인데 전부 5초 걸리는" 상황을 감지하지 못합니다.

이 항목은 [1. 리액티브 파이프라인의 블로킹 I/O](01-reactive-blocking-io.md)와 맞물립니다 —
우리 쪽 이벤트 루프가 막혀 외부 호출이 느려져도 `slowCall` 조건에 걸려 회로가 열립니다.
**원인이 우리 안에 있는데 로그는 외부 API 장애를 가리키는** 오진 경로입니다.

---

## 4. DB 커넥션 풀 보호 — 트랜잭션 경계를 좁힌다

> [`AiResultRetrievalService.java`](../../src/main/java/com/serverbe/application/service/AiResultRetrievalService.java)

AI 결과 처리는 S3 다운로드, S3 삭제, SSE 발송 등 **긴 네트워크 I/O**를 포함합니다.
메서드 전체에 `@Transactional`을 걸면 그동안 DB 커넥션을 점유해 풀이 고갈됩니다.

`TransactionTemplate`으로 **DB 쓰기 구간만** 원자적으로 감싸고 외부 I/O는 트랜잭션 밖으로 뺐습니다.

`@Transactional` 애노테이션 대신 `TransactionTemplate`을 쓴 이유가 여기 있습니다.
애노테이션은 **메서드 전체**가 경계입니다. 경계를 메서드보다 좁게 잡으려면 프로그래밍 방식이 필요합니다.

같은 판단이 [3. SQS 콜백 경합](03-sqs-callback-race-condition.md)에도 있습니다.
그쪽은 커넥션뿐 아니라 **비관적 락**까지 붙잡는 문제라 더 치명적입니다.

---

## 5. 준영속 엔티티가 부른 불필요한 SELECT

> [`AiGenerationService.java`](../../src/main/java/com/serverbe/application/service/AiGenerationService.java)

도메인 모델이 불변이라 상태 전이는 항상 **"조회 → 값 이관 → 저장"** 입니다.

트랜잭션 없이 저장을 호출하면 어댑터의 `findById`가 **자기 트랜잭션을 열고 닫아** 엔티티가 준영속이
되고, 이어지는 저장이 `merge`를 유발해 **SELECT 두 번 + UPDATE 한 번**이 나갑니다.
상태 전이 구간을 `TransactionTemplate`으로 묶으면 조회 결과가 관리 상태로 남아 `merge`가 추가 조회 없이
끝납니다.

**그런데 신규 생성(INSERT) 경로에는 일부러 적용하지 않았습니다.**

그쪽은 `active_user_id` 유니크 위반을 `AiTaskPersistenceAdapter.save` 내부의 `catch`에서
`DUPLICATE_AI_REQUEST`로 변환합니다. 바깥 트랜잭션이 있으면 **위반이 커밋 시점으로 밀려**
그 `catch`를 그대로 빠져나갑니다. 사용자는 친절한 "이미 진행 중인 작업이 있습니다" 대신 500을 받습니다.

"트랜잭션은 넓게 걸수록 안전하다"는 직관이 두 번 연속 틀리는 자리입니다.
같은 함정을 [7. 소셜 계정 중복 §5-3](07-oauth-duplicate-account.md#5-3-경합에서-진-요청을-살려-보내기--requires_new)에서는
`REQUIRES_NEW`로 풀었습니다. **제약 위반을 잡아 처리하려면 그 위반이 언제 발생하는지를 통제해야 합니다.**

---

## 6. PII 필드 암호화와 무중단 키 교체

> [`CryptoConverter.java`](../../src/main/java/com/serverbe/adapter/out/persistence/converter/CryptoConverter.java) · [`AesGcmEncryptor.java`](../../src/main/java/com/serverbe/adapter/out/crypto/AesGcmEncryptor.java)

이메일 등 민감 정보를 JPA `AttributeConverter`로 **AES-GCM 자동 암복호화**합니다.
서비스 코드는 평문을 다루고, 영속화 경계에서만 암호화가 일어납니다.

키 교체가 설계의 핵심입니다. **암호문에 키 버전을 새겨** 두고, 구버전 키로 암호화된 데이터를 읽으면
마이그레이션 대상으로 표시해 점진적으로 재암호화합니다.

**왜 일괄 재암호화가 아닌가** — 전 사용자 데이터를 한 번에 다시 쓰려면 그동안 서비스를 멈추거나
긴 트랜잭션을 잡아야 합니다. 점진적 방식은 **읽는 김에 고칩니다.** 활성 사용자부터 자연스럽게
새 키로 옮겨 가고, 구키는 마지막 사용자가 넘어갈 때까지만 유지하면 됩니다.

활성 버전은 `application.yml`의 `encryption.active-version`이고, 키는 `ENCRYPTION_SECRET_KEY_V1`/`_V2`
환경변수로 주입됩니다. **값이 비어 있으면 기동에 실패**합니다 — 복호화 키가 없으면 기존 데이터를
읽을 수 없으므로, 조용히 뜨는 것보다 못 뜨는 편이 안전합니다.

---

## 7. 다중 인스턴스 SSE

> [`SseRedisPublishAdapter.java`](../../src/main/java/com/serverbe/adapter/out/notification/SseRedisPublishAdapter.java) · [`SseRedisMessageListener.java`](../../src/main/java/com/serverbe/adapter/in/messaging/SseRedisMessageListener.java) · [`SseEmitterRegistry.java`](../../src/main/java/com/serverbe/adapter/in/web/sse/SseEmitterRegistry.java) · [`RedisPubSubConfig.java`](../../src/main/java/com/serverbe/infrastructure/config/RedisPubSubConfig.java)

SSE 연결은 **특정 인스턴스의 메모리**에 고정됩니다(`Map<String, Set<SseEmitter>>`).
그런데 완료 이벤트는 **다른 인스턴스**에서 발생할 수 있습니다. SQS 메시지를 어느 태스크가 받을지
정해져 있지 않기 때문입니다.

이벤트를 만든 인스턴스가 자기 메모리만 뒤지면, 클라이언트가 다른 인스턴스에 붙어 있을 때
**아무 일도 일어나지 않습니다.** 사용자는 완료된 작업을 무한히 기다립니다.

Redis Pub/Sub으로 이벤트를 브로드캐스트해, **어느 인스턴스가 받든 모든 인스턴스가 듣고**
자기 메모리에 해당 클라이언트가 있으면 발송합니다.

이 세 가지 — 커넥션 보관, Redis 발행, Redis 수신 후 전송 — 는 원래 `SseNotificationAdapter` 한 클래스에
있었습니다. 그 탓에 아웃바운드 포트인 `TaskNotificationPort`가 `SseEmitter`(spring-webmvc 타입)를
시그니처에 노출했습니다. 지금은 **방향에 맞게 셋으로 나뉘어 있습니다** — 발행만 하는
`SseRedisPublishAdapter`(아웃바운드), 수신하는 `SseRedisMessageListener`(인바운드), 커넥션을 들고 있는
`SseEmitterRegistry`(웹). 발행측과 수신측이 함께 쓰는 `SsePubSubMessage`는 어느 한쪽 어댑터에 두면
`adapter.in → adapter.out` 의존이 생기므로 포트 DTO로 올렸습니다.

```java
redisTemplate.convertAndSend(sseChannel, jsonMessage);
```

**구독 시점의 경합도 함께 막았습니다.** `subscribe`는 `CONNECTED` 이벤트를 보낸 뒤 **DB 상태를 다시
읽어**, 이미 종결된 작업이면 즉시 완료 이벤트를 보내고 연결을 닫습니다. 클라이언트가 연결하기 직전에
작업이 끝나면 브로드캐스트를 놓치기 때문입니다.

**왜 sticky session이 아닌가** — ALB 세션 어피니티로 붙이면 인스턴스가 교체될 때(롤링 배포마다 일어납니다)
연결이 끊깁니다. 그리고 **이벤트를 만든 쪽과 연결을 가진 쪽이 다르다는 문제는 그대로**입니다.

### 함께 고친 것 — 채널 이름이 두 곳에서 정의되고 있었다

발행 측은 `application.yml`의 `sse.channel`을 읽었습니다.

```yaml
sse:
  channel: "sse-notifications"
```

구독 측은 **하드코딩**되어 있었습니다.

```java
@Bean
public ChannelTopic sseTopic() {
    return new ChannelTopic("sse-notifications");
}
```

두 값이 우연히 같아 동작했을 뿐입니다. **yml만 바꾸면 발행과 구독이 서로 다른 채널을 보게 되고,
그때 증상은 "알림이 안 온다"뿐**입니다. 예외도 로그도 남지 않습니다. 위 분해 작업과 함께
`RedisPubSubConfig`도 `SseProperties`를 주입받아, 지금은 채널 이름을 **한 곳에서만** 읽습니다.

> **설정값이 두 곳에 있으면 그 둘이 어긋난 날 침묵으로 실패합니다.** 하드코딩된 쪽이 틀린 게 아니라
> **두 곳에 있다는 것 자체가 결함**입니다.

---

## 8. 스키마 관리를 `ddl-auto`에서 Flyway로 이관

> [`V2__add_active_task_slot.sql`](../../src/main/resources/db/migration/V2__add_active_task_slot.sql) · [`V3__add_users_oauth_unique.sql`](../../src/main/resources/db/migration/V3__add_users_oauth_unique.sql)

동시 요청을 막는 유니크 제약을 추가하면서 `ddl-auto: update`에 맡길 수 없다고 판단했습니다.

Hibernate의 `update`는 컬럼 추가는 해 주지만 **기존 테이블에 유니크 제약을 붙여준다는 보장이 없고,
새 컬럼에 기존 행을 백필할 수도 없습니다.** 무엇보다 **순서를 표현할 수 없습니다.**

실제로 마이그레이션 대상 DB에는 한 사용자에게 진행 중 작업이 **7건** 쌓여 있었습니다.
정리 없이는 유니크 인덱스 생성 자체가 실패하는 상태였습니다.

`기존 중복 정리 → 컬럼 추가 → 백필 → 제약 생성` 순서를 명시적 SQL로 작성하고 `ddl-auto`는 `validate`로
낮춰, **스키마 변경 권한을 한 곳으로 모았습니다.**

이후 소셜 계정 유니크 제약을 추가할 때도 `기존 중복 정리 → 자식 데이터 이관 → 제약 생성`이라는
같은 순서를 그대로 따랐습니다([7번 문서](07-oauth-duplicate-account.md#5-2-기존-중복-정리--순서가-전부다)).
**제약을 추가하는 일은 언제나 "데이터를 먼저 맞추고 제약을 나중에"** 입니다.

다만 `validate`에는 사각지대가 남습니다 —
[9. 스키마 드리프트](09-schema-drift-flyway-hibernate.md#남은-과제) 참고.

---

## 9. 표준화된 에러 응답

> [`BusinessExceptionHandler.java`](../../src/main/java/com/serverbe/adapter/in/web/error/BusinessExceptionHandler.java) · [`RestApiResponse.java`](../../src/main/java/com/serverbe/adapter/in/web/response/RestApiResponse.java)

도메인별 `ErrorCode` enum과 `BusinessException` 계층을 정의하고 `@RestControllerAdvice`에서 일괄
변환해, 모든 API가 동일한 응답 포맷을 갖도록 했습니다.

```json
{"success":false,"httpStatus":"BAD_REQUEST","error":{"code":"JWT_002","message":"JWT 토큰 값이 비어있습니다."}}
```

에러 코드를 문자열 상수가 아니라 **enum**으로 둔 것이 요점입니다. 코드·HTTP 상태·메시지가 한 자리에
묶여 있어 셋이 어긋날 수 없고, 새 에러를 추가할 때 빠뜨릴 수 있는 필드가 없습니다.

핸들러는 처리하지 못한 예외를 `[UNHANDLED INTERNAL ERROR]` 태그로 남깁니다.
**"우리가 예상한 실패"와 "예상하지 못한 실패"를 로그에서 구분할 수 있어야** 후자를 줄여 나갈 수 있습니다.

---

## 10. 죽은 permitAll 경로 — 배포 검증이 덤으로 잡은 것

[10번 문서](10-sqs-listener-startup-failure.md)의 도커 기동 검증 중, 스모크 테스트로 permitAll 경로를
하나씩 찔러 보다가 드러난 문제입니다.

`SecurityConfig`의 permitAll 목록에는 `/api/v1/running-arts/sample`이 있었는데,
**`RunningArtController`에 이 경로를 받는 핸들러가 없었습니다.**

요청은 `GET /api/v1/running-arts/{runningArtId}` 매핑으로 흘러가고, `"sample"`을 `Long`으로 변환하다
실패해 **404가 아니라 500**이 났습니다.

```
MethodArgumentTypeMismatchException: Method parameter 'runningArtId':
    Failed to convert value of type 'java.lang.String' to required type 'java.lang.Long';
    For input string: "sample"
```

인증 없이 접근 가능한 경로였으므로 **누구나 500을 유발할 수 있었습니다.** 심각한 취약점은 아니지만,
보안 설정에 실체 없는 규칙이 남아 있다는 것 자체가 신호입니다.

**해결** · `TEST_API_PATHS` 상수와 그 `requestMatchers(...).permitAll()` 등록을 함께 제거했습니다.
이제 이 경로는 `anyRequest().authenticated()`에 걸려 인증 없이는 도달하지 못합니다.

이 항목이 남긴 교훈은 따로 있습니다. **permitAll 목록은 "열어 둔 문"의 목록인데, 그 문 뒤에 방이
있는지는 아무도 검사하지 않습니다.** 엔드포인트가 사라져도 규칙은 남고, 남은 규칙은 다음 사람에게
"여기 뭔가 있다"고 잘못 알려 줍니다. 스모크 테스트가 permitAll 경로를 한 번씩 찔러 보는 것만으로
이런 종류의 잔재가 드러납니다.
