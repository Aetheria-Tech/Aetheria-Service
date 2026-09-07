# 1. 리액티브 파이프라인의 블로킹 I/O — 전 구간 스레드 격리

> 요약 · [README — 1. Reactor 이벤트 루프 블로킹](../../README.md#1-reactor-이벤트-루프-블로킹--전-구간-스레드-격리)
> 전제 · [서블릿 스택 위의 Reactor — 이 서버는 Tomcat에서 돈다](../architecture/servlet-and-reactor.md)
> 근거 · [`AiGenerationService.java`](../../src/main/java/com/serverbe/application/service/AiGenerationService.java) · [`AiGenerationController.java`](../../src/main/java/com/serverbe/adapter/in/web/AiGenerationController.java) · [`WebClientConfig.java`](../../src/main/java/com/serverbe/infrastructure/config/WebClientConfig.java)

## 1. 상황 — 이 서버는 Semi-Reactive다

먼저 전제를 정확히 해 둘 필요가 있습니다. 이 애플리케이션은 **순수 WebFlux 서버가 아닙니다.**

`spring-boot-starter-web`과 `spring-boot-starter-webflux`가 함께 있으면 Spring Boot는 **서블릿 스택을
선택**합니다. 실제로 기동 로그에는 `Tomcat started on port 8080`이 찍히고, **Netty 서버는 뜨지 않습니다.**
`ServerBeApplication`의 주석이 이 구조를 **Semi-Reactive**라고 부르는 것도 같은 뜻입니다.
왜 그렇게 결정되는지, 컨트롤러가 반환하는 `Mono`가 서블릿 위에서 어떻게 처리되는지는
[서블릿 스택 위의 Reactor](../architecture/servlet-and-reactor.md)에 따로 정리했습니다.

- **인바운드 HTTP** — Tomcat 서블릿 스레드가 받습니다.
- **아웃바운드 외부 호출** — 카카오·구글 OAuth, 카카오 지오코딩, Discord 웹훅은 `WebClient`를 쓰고,
  이때 **Reactor Netty 이벤트 루프(`reactor-http-nio-*`)** 위에서 응답이 처리됩니다.
- **AI 파이프라인** — 컨트롤러가 `Mono<ResponseEntity<...>>`를 반환하는 비동기 서블릿 경로입니다.

```java
// AiGenerationController.java
@PostMapping
public Mono<ResponseEntity<RestApiResponse<String>>> initiateGeneration(...)
```

AI 파이프라인의 **첫 외부 호출이 지오코딩 `WebClient` 호출**입니다(그 앞의 Rate Limit 검증은 이미
`boundedElastic`에 있습니다). Reactor에서 연산자는 별도 지정이 없으면
**직전 신호를 방출한 스레드에서 이어 실행**됩니다. 즉 지오코딩 응답 이후의 모든 단계는 기본적으로
`reactor-http-nio` 이벤트 루프 스레드 위에서 돕니다.

그런데 그 뒤에 이어지는 것들이 전부 블로킹 클라이언트입니다.

- **JPA(Hibernate)** — JDBC는 동기입니다. 프로젝트에 R2DBC 의존성 자체가 없습니다.
- **Redis** — Lettuce를 쓰지만 `RedisTemplate` 동기 API로 호출합니다.
- **AWS SDK v2** — `S3Client`, `SageMakerRuntimeClient` 모두 동기 클라이언트입니다.

## 2. 증상

AI 요청이 몰리면 **AI와 무관한 기능까지 함께 느려집니다.** 특히 눈에 띄는 것은 **소셜 로그인**입니다.
AI 생성을 요청한 적도 없는 사용자가 카카오 로그인에서 지연을 겪습니다.

더 나쁜 2차 효과가 있습니다. 외부 API 호출이 전반적으로 느려지면 Resilience4j의
`slowCallDurationThreshold: 2500ms` / `slowCallRateThreshold: 50` 조건에 걸려 **카카오·구글 서킷이
열립니다.** 상대 서버는 멀쩡한데 우리 쪽 스레드가 막혀서 회로가 열리는 것이라, 로그만 보면
"카카오 API 장애"로 오진하기 딱 좋습니다.

## 3. 원인

Reactor Netty의 이벤트 루프 스레드는 **CPU 코어 수만큼만** 존재합니다. Fargate 0.5 vCPU 태스크에서는
사실상 한 줌입니다. 그리고 이 이벤트 루프 그룹은 **애플리케이션의 모든 `WebClient`가 공유**합니다.

AI 파이프라인이 그 스레드 위에서 JDBC 응답을 기다리며 `park` 상태로 들어가면, 같은 스레드에 배정된
**카카오 로그인 응답, 구글 토큰 발급, 지오코딩, Discord 알림이 전부 함께 멈춥니다.** 블로킹 한 번의
피해 반경이 "AI 요청 하나"가 아니라 "그 순간 그 스레드를 쓰는 모든 아웃바운드 트래픽"입니다.

서블릿 스레드 풀이라면 200개 중 하나가 막혀도 나머지가 일합니다. 이벤트 루프에서는 스레드 하나가
아웃바운드 처리량의 1/N을 통째로 들고 있습니다. **같은 블로킹 코드가 리액티브 체인 안에서는 훨씬 큰
피해를 냅니다.**

## 4. 검토한 대안

| 대안 | 기각 이유 |
| --- | --- |
| AI 파이프라인을 동기 코드로 되돌린다 | 외부 API 대기가 긴 작업이라 애초에 리액티브로 만든 이유가 사라집니다. 지오코딩 → S3 → SageMaker를 순차로 기다리는 동안 톰캣 스레드를 붙잡게 되고, 동시 요청 수만큼 스레드가 묶입니다. |
| JPA를 R2DBC로 교체 | Querydsl 동적 쿼리, `AttributeConverter` 기반 PII 암호화, 비관적 락 등 JPA에 강하게 묶인 코드가 전부 재작성 대상입니다. 얻는 것에 비해 파급이 지나칩니다. |
| `WebClient` 전용 이벤트 루프 그룹을 분리 | AI 파이프라인이 쓰는 루프를 따로 떼어내도 **그 루프 안에서는 여전히 블로킹**입니다. 피해 반경만 줄일 뿐 원인은 남습니다. |
| `block()`으로 그냥 기다린다 | 이벤트 루프에서 호출하면 Reactor가 `IllegalStateException`을 던집니다. 던지지 않는 스레드에서 호출하더라도 문제 자체는 그대로입니다. |
| 블로킹 구간만 별도 `Executor` + `CompletableFuture` | 하는 일은 같은데 Reactor 컨텍스트(에러 전파, 취소, `onErrorResume` 보상 체인)와 끊깁니다. `subscribeOn`이 같은 일을 스트림 안에서 해 줍니다. |

## 5. 해결

파이프라인에서 **블로킹이 일어나는 모든 구간**을 `Mono.fromCallable(...)`/`Mono.fromRunnable(...)`로 감싸고
`.subscribeOn(Schedulers.boundedElastic())`을 붙여 전용 스레드 풀로 격리했습니다.
`boundedElastic`은 정확히 이 용도(블로킹 작업 격리)를 위해 Reactor가 제공하는 스케줄러입니다.

| 단계 | 메서드 | 무엇이 블로킹인가 |
| --- | --- | --- |
| 요청 자격 검증 | `validateRequestEligibility` | Redis `tryLock` + JPA `existsActiveTaskByUserId` |
| PENDING 저장 | `savePendingTask` | JPA INSERT |
| Step 1 | `processExternalAiServices` 내부 | S3 `uploadInputJson` |
| Step 2 | `processExternalAiServices` 내부 | SageMaker `invokeAsync` |
| 보상 | `compensateS3Upload` / `compensateAfterExternalSuccess` | S3 삭제 |
| 상태 반영 | `saveProcessingTask` / `handlePipelineError` | JPA UPDATE |

```java
private Mono<Void> validateRequestEligibility(Long userId) {
    return Mono.fromRunnable(() -> {
        // 1차 방어: Redis 기반 5초 연타 방지 (따닥 방어)
        if (!taskRateLimitPort.tryLock(userId, 5)) { ... }
        // 2차 방어: DB 기반 비즈니스 로직 체크 (진행 중인 작업 존재 여부)
        if (taskQueryPort.existsActiveTaskByUserId(userId)) { ... }
    }).subscribeOn(Schedulers.boundedElastic()).then();
}
```

**예외 없이 전부 적용한 것이 요점입니다.** 여섯 군데 중 한 곳만 빠뜨려도 장애는 그대로 재현됩니다.
"이 정도는 빠르니까 괜찮겠지"가 통하지 않습니다. 평소 1ms인 쿼리도 커넥션 풀이 포화되면 대기합니다.

### 5-1. 일부러 감싸지 않은 곳

`buildPromptJson`은 `Mono.fromCallable`로만 감싸고 `subscribeOn`을 붙이지 않았습니다. Jackson 직렬화는
**순수 CPU 작업**이라 이벤트 루프에서 실행해도 park되지 않습니다. 블로킹이 아닌 코드까지
`boundedElastic`으로 보내면 스레드 전환 비용만 늘고 얻는 것이 없습니다.
**격리는 블로킹 I/O에 대한 처방이지, 습관이 아닙니다.**

### 5-2. UPDATE 경로만 트랜잭션으로 묶는다

같은 클래스의 `transactionTemplate` 필드에는 긴 판단이 붙어 있습니다. 스레드 격리와 함께 읽어야
전체 그림이 보입니다.

```java
/**
 * 트랜잭션 없이 taskUpdatePort.save(...) 를 호출하면 어댑터의 findById 가 자기 트랜잭션을
 * 열고 닫아 엔티티가 준영속이 되고, 이어지는 저장이 merge 를 유발해 SELECT 두 번 + UPDATE 한 번이
 * 나갑니다. 트랜잭션 안에서는 조회 결과가 관리 상태로 남아 merge 가 추가 조회 없이 끝납니다.
 *
 * 신규 생성(INSERT) 경로에는 적용하지 않습니다. 그쪽은 active_user_id 유니크 위반을
 * AiTaskPersistenceAdapter.save 내부에서 잡아 DUPLICATE_AI_REQUEST 로 변환하는데,
 * 바깥 트랜잭션이 있으면 위반이 커밋 시점으로 밀려 그 catch 를 빠져나가기 때문입니다.
 */
private final TransactionTemplate transactionTemplate;
```

즉 **UPDATE 경로에는 트랜잭션을 걸고, INSERT 경로에는 일부러 걸지 않습니다.** "트랜잭션은 넓게 걸수록
안전하다"는 직관이 여기서는 틀립니다. 바깥 트랜잭션이 제약 위반을 커밋 시점까지 미루면, 그 위반을 잡아
사용자 친화적인 에러로 바꾸는 `catch`가 아예 실행되지 않습니다. 자세한 내용은
[11. 설계 기록 — 준영속 엔티티](11-design-notes.md#5-준영속-엔티티가-부른-불필요한-select)에 있습니다.

## 6. 검증

- **점유량 측정** — 이 판단이 실제로 워커를 덜 잡는지는 별도 하네스로 잽니다. 동기(`RestClient`)와
  논블로킹(`WebClient`)이 같은 스텁을 같은 타임아웃으로 부르는 두 경로에 JMeter로 같은 부하를 흘리고,
  `tomcat.threads.busy`를 Actuator에서 긁습니다. 측정 조건과 결과는
  [측정 01 — 스레드 점유](../benchmark/01-thread-occupancy.md)에 있습니다.
- **스레드 이름 확인** — 격리가 제대로 되면 블로킹 구간은 `boundedElastic-N`, 지오코딩 응답 처리는
  `reactor-http-nio-N`으로 찍힙니다. 블로킹 구간 로그에서 `reactor-http-nio-`가 보이면 그 지점이 빠진 것입니다.

  ```bash
  docker compose logs app | grep -E "boundedElastic|reactor-http-nio"
  ```
- **격리 확인(부하)** — AI 요청을 다수 발생시킨 상태에서 **소셜 로그인 시작 엔드포인트**의 응답 시간을 잽니다.
  이 경로도 `WebClient`를 타므로, 격리가 깨져 있으면 여기서 지연이 드러납니다.

  ```bash
  curl -w "%{time_total}\n" -o /dev/null -s "http://localhost:8080/api/v1/auth/login/kakao"
  ```
- **서킷 상태 확인** — 아웃바운드가 막히면 서킷이 열립니다. 부하 전후로 비교합니다.

  ```bash
  curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
  ```

## 7. 남은 과제

- **BlockHound 도입** — 지금은 "빠뜨리지 않았는지"를 코드 리뷰로 확인합니다. 테스트 소스셋에만
  BlockHound를 붙이면 이벤트 루프 블로킹이 **테스트 실패로** 드러납니다. 새 블로킹 호출이 추가될 때
  자동으로 잡히는 안전망이 없다는 것이 현재 구조의 약점입니다.
- ~~**README 문구**~~ — README 1번 항목이 이 문제를 "WebFlux 이벤트 루프"라고 부르고 피해 반경을
  "서버 전체의 모든 요청"이라고 적고 있던 것을 정정했습니다. 정확히는 **서블릿 스택 위에서 `WebClient`가
  쓰는 Reactor Netty 이벤트 루프**이고, 피해 반경은 **모든 아웃바운드 외부 API 호출**입니다.
  전제 자체는 [서블릿 스택 위의 Reactor](../architecture/servlet-and-reactor.md)로 분리했습니다.
