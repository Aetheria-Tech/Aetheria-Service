# 5. Rate Limiting — Lua 원자적 토큰 버킷과 서킷 브레이커 폴백

> 요약 · [README — 5. Rate Limiting](../../README.md#5-rate-limiting--lua-원자적-토큰-버킷과-서킷-브레이커-폴백)
> 근거 · [`token_bucket.lua`](../../src/main/resources/scripts/token_bucket.lua) · [`RateLimiterService.java`](../../src/main/java/com/serverbe/application/service/RateLimiterService.java) · [`RateLimitAspect.java`](../../src/main/java/com/serverbe/infrastructure/config/aop/RateLimitAspect.java) · [`RateLimitFallbackHandler.java`](../../src/main/java/com/serverbe/adapter/out/persistence/ratelimit/RateLimitFallbackHandler.java) · [`RateLimit.java`](../../src/main/java/com/serverbe/application/annotation/RateLimit.java)

## 1. 상황

SageMaker 비동기 추론은 **호출 1건당 비용이 발생**합니다. 일반적인 API 남용은 CPU를 조금 더 쓰는 문제지만,
여기서는 **버튼 연타가 그대로 청구서**입니다. 지오코딩 API도 마찬가지로 유료 호출입니다.

그래서 레이트 리밋이 "있으면 좋은 것"이 아니라 **비용 방어선**입니다.

## 2. 증상

초기 구현은 Redis 카운터를 `GET → 계산 → SET`으로 다뤘습니다. 이 세 명령 사이에 다른 요청이 끼어들면
**둘 다 "아직 한도 이내"라고 판단**합니다.

```
요청 A: GET count=4  →              → 계산(5 ≤ 5, 허용) → SET 5
요청 B:              GET count=4    → 계산(5 ≤ 5, 허용) → SET 5
결과: 한도 5인데 6번째 호출까지 통과, 카운터는 5로 남음
```

연타할수록 창이 더 자주 열리므로, **막으려는 상황에서 가장 잘 뚫리는** 구조였습니다.

## 3. 원인

Redis 명령 각각은 원자적이지만 **"조회 → 계산 → 갱신"이라는 시퀀스 전체는 원자적이지 않습니다.**
애플리케이션이 계산하는 순간 Redis는 다른 클라이언트의 명령을 받습니다. 인스턴스가 여러 대면 창은 더 넓어집니다.

## 4. 검토한 대안

| 대안 | 기각 이유 |
| --- | --- |
| `INCR` + `EXPIRE` 고정 윈도 | `INCR`은 원자적이라 경합은 사라지지만 **경계 문제**가 생깁니다. 윈도가 바뀌는 순간을 노리면 짧은 시간에 한도의 2배가 통과합니다. 또 고정 윈도는 "잠시 쉬었다가 몰아서 보내는" 정상 사용 패턴을 구분하지 못합니다. |
| `WATCH`/`MULTI` 낙관적 트랜잭션 | 충돌 시 재시도가 필요하고, 경합이 심할수록 재시도가 늘어납니다. 정확히 **연타 상황에서 가장 비효율적**입니다. |
| Bucket4j 등 라이브러리 | 분산 백엔드를 붙이면 결국 같은 원자성 문제를 라이브러리가 어떻게 푸는지에 의존하게 됩니다. 스크립트 30줄로 끝나는 일에 의존성과 학습 비용을 더할 이유가 없었습니다. |
| DB 유니크 제약만으로 "1인 1작업" 방어 | 이것도 **씁니다**(2차 방어). 다만 DB까지 가는 요청 자체를 줄이지 못하고, 연타를 막지도 못합니다(작업이 끝나면 다시 통과). 비용 지점 앞에서 먼저 걸러야 합니다. |

## 5. 해결

### 5-1. 원자성 — 단일 Lua 스크립트

조회·리필 계산·갱신을 **하나의 Lua 스크립트**로 옮겼습니다. Redis는 스크립트를 원자적으로 실행하므로
중간에 다른 명령이 끼어들 수 없습니다.

```lua
local last_tokens = tonumber(redis.call("HGET", key, "tokens"))
local last_refilled = tonumber(redis.call("HGET", key, "last_refilled"))

if last_tokens == nil then          -- 콜드 스타트: 버킷을 가득 채운 상태로 시작
  last_tokens = capacity
  last_refilled = now
end

local delta = math.max(0, now - last_refilled)
local filled_tokens = math.min(capacity, last_tokens + (delta * rate / 1000))

local allowed = false
if filled_tokens >= requested then
  allowed = true
  filled_tokens = filled_tokens - requested
end

redis.call("HSET", key, "tokens", filled_tokens, "last_refilled", now)
redis.call("EXPIRE", key, 3600)
return allowed
```

읽을 때 주의할 점 몇 가지입니다.

- **`rate`는 초당 토큰, `delta`는 밀리초** — 그래서 `delta * rate / 1000`으로 단위를 맞춥니다.
- **콜드 스타트는 가득 찬 버킷** — 처음 오는 사용자를 차단하지 않기 위해서입니다. 반대로 하면
  신규 사용자가 첫 요청부터 막힙니다.
- **`math.max(0, ...)`** — 시계 역행(NTP 보정)으로 `now < last_refilled`가 되어도 음수 리필이
  일어나지 않게 합니다.
- **거부해도 `last_refilled`를 갱신합니다** — `HSET`이 `if` 밖에 있습니다. 거부된 요청도 타임스탬프를
  덮어쓰므로, **연타 중에는 리필 기준 시각이 계속 밀립니다.** 남용자에게 불리하게 작동하므로 이 용도에는
  맞지만, 엄밀한 토큰 버킷 정의와는 다릅니다. (아래 남은 과제)
- **`EXPIRE 3600`** — 다시 오지 않는 IP·사용자의 키가 Redis에 영구히 쌓이는 것을 막습니다.

### 5-2. `capacity`와 `refillRate`를 분리한 이유

`capacity`는 **버스트 허용량**, `refillRate`는 **장기 평균 처리율**입니다. 둘을 분리했기 때문에
"평소엔 초당 5회지만 잠시 쉬었다 온 사용자는 한 번에 10회까지 허용"이라는 정책이 표현됩니다.

하나의 숫자로 관리하는 고정 윈도는 이 구분을 못 합니다. 정상 사용자를 막지 않으면서 지속적 남용만
차단하려면 두 축이 필요합니다.

### 5-3. 다층 방어 — 비용 지점 **앞**에 배치

비용이 발생하는 곳보다 먼저 걸러야 의미가 있습니다. AI 파이프라인의 첫 단계가 검증인 이유입니다.

1. **1차 — Redis 5초 연타 방지** (`taskRateLimitPort.tryLock(userId, 5)`)
2. **2차 — DB "1인 1작업" 검증** (`existsActiveTaskByUserId`)
3. 그다음에야 **지오코딩 호출**(유료) → S3 → SageMaker(유료)

지오코딩 API 호출조차 이 두 관문을 통과해야 도달합니다.

### 5-4. 선언적 적용 — `@RateLimit`

```java
@RateLimit(target = RateLimit.TargetType.IP, capacity = 10, refillRate = 5)
@RateLimit(target = RateLimit.TargetType.USER, capacity = 5, refillRate = 3)
@GetMapping("/nearby")
public ... findNearby(...)
```

`@Repeatable`로 만들어 **한 엔드포인트에 IP·USER 정책을 동시에** 걸 수 있습니다. IP 단위는 계정을
여러 개 만드는 남용을, USER 단위는 한 계정의 과다 호출을 각각 막습니다. 둘은 서로를 대체하지 못합니다.

`RateLimitAspect`가 `@within(RestController)` 포인트컷으로 컨트롤러 메서드를 가로채고,
`getAnnotationsByType`으로 붙은 정책을 모두 읽어 순서대로 검사합니다. 위반 시
`RateLimitExceededException`을 던져 전역 예외 핸들러가 표준 응답으로 변환합니다.

### 5-5. Redis가 죽었을 때 — 서킷 브레이커와 **비대칭** 폴백

레이트 리미터가 죽었다고 서비스 전체가 멈춰선 안 됩니다. `RateLimiterService`의 두 메서드는 각각
`@CircuitBreaker(name = "redisRateLimit", fallbackMethod = ...)`로 감싸여 있습니다.

폴백의 동작이 **USER와 IP에서 의도적으로 다릅니다.**

```java
// USER — Caffeine 로컬 캐시로 축소된 방어선을 유지 (한도 초과 시 차단)
if (currentCount > capacity) {
    log.warn("[L1 Local 방어막] 한도 초과 차단! Key: {}, 현재 요청 수: {}", key, currentCount);
    return false;
}
```

```java
// IP — 무조건 허용 (Fail-Open)
public boolean handleIpFallback(String ip, String endpoint, int ipCapacity, int ipRefillRate, Throwable t) {
    log.error("[Circuit Breaker] Redis 장애 감지! IP {}의 요청을 무조건 허용(Fail-Open). 사유: {}", ip, t.getMessage());
    return true;
}
```

이유는 **막으려는 대상이 다르기 때문**입니다.

- **USER** — 비용이 발생하는 요청은 로그인한 사용자만 보낼 수 있습니다. 여기는 Redis가 죽어도
  지켜야 합니다. 인스턴스별 로컬 카운팅이라 정확한 전역 한도는 아니지만, **한 인스턴스가 감당하는
  분량만큼은** 막습니다. 축소된 방어선이지 방어선의 부재가 아닙니다.
- **IP** — IP 제한은 비용 방어가 아니라 트래픽 위생에 가깝습니다. Redis 장애 중에 IP 제한 때문에
  **정상 사용자의 로그인·조회까지 막으면** 장애를 우리가 확대하는 셈입니다. 가용성을 택했습니다.

로그 레벨도 다릅니다. USER 폴백은 `INFO`/`WARN`, IP 폴백은 `ERROR`입니다. **Fail-Open은 위험을 감수한
결정**이므로 반드시 눈에 띄어야 합니다.

## 6. 검증

- **동시성 테스트** — [`RateLimiterServiceConcurrencyTest.java`](../../src/test/java/com/serverbe/RateLimiterServiceConcurrencyTest.java)
  가 실제 Redis에 다수 스레드로 동시 요청을 보내 **허용된 횟수가 정확히 `capacity`인지** 확인합니다.
  Lua 이전 구현은 이 테스트를 통과하지 못합니다.

  ```bash
  docker compose up -d redis
  ./gradlew integrationTest --tests "com.serverbe.RateLimiterServiceConcurrencyTest"
  ```
- **단위 테스트** — [`RateLimiterServiceTest.java`](../../src/test/java/com/serverbe/application/service/RateLimiterServiceTest.java),
  [`RateLimitAspectTest.java`](../../src/test/java/com/serverbe/infrastructure/config/aop/RateLimitAspectTest.java)
- **버킷 상태 직접 확인**

  ```bash
  docker compose exec redis redis-cli --scan --pattern 'rate:*'
  docker compose exec redis redis-cli HGETALL "rate:user:1:/api/v1/running-arts/nearby"
  ```
- **폴백 재현** — Redis를 내린 뒤 요청을 보내면 서킷이 열리고 폴백 로그가 찍힙니다.

  ```bash
  docker compose stop redis
  curl -i http://localhost:8080/api/v1/running-arts/nearby   # IP는 통과, USER는 로컬 한도 적용
  docker compose logs app | grep -E "Circuit Breaker|L1 Local"
  docker compose start redis
  ```

## 7. 남은 과제

- **거부 시에도 `last_refilled`를 갱신**하는 동작은 남용 억제에는 유리하지만, 표준 토큰 버킷과 다릅니다.
  거부된 요청이 리필 시계를 미루므로 **정상 사용자가 순간적으로 한도를 넘겼을 때 회복이 느려집니다.**
  `HSET`을 `allowed`일 때만 수행하도록 바꿀지는 실사용 지표를 보고 판단할 문제입니다.
- `RateLimitAspect`의 포인트컷은 `@within(RestController)`이라 **모든 컨트롤러 메서드가 어드바이스를
  통과**합니다. `@RateLimit`이 없으면 곧바로 진행하므로 동작은 옳지만, 애노테이션이 붙은 메서드만
  대상으로 하는 포인트컷보다 불필요한 프록시 호출이 많습니다.
- 폴백의 Caffeine 캐시는 **인스턴스 로컬**이라 전역 한도를 보장하지 않습니다. Redis 장애가 길어지면
  실질 한도는 `capacity × 인스턴스 수`가 됩니다. 문서화된 한계로 남겨 둡니다.
