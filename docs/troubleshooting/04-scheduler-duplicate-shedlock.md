# 4. 스케줄러 중복 실행 — ShedLock 분산 락

> 요약 · [README — 4. 스케줄러 중복 실행](../../README.md#4-스케줄러-중복-실행--shedlock-분산-락)
> 근거 · [`TaskTimeoutScheduler.java`](../../src/main/java/com/serverbe/adapter/in/scheduler/TaskTimeoutScheduler.java) · [`ShedLockConfig.java`](../../src/main/java/com/serverbe/infrastructure/config/ShedLockConfig.java)

## 1. 상황

AI 작업은 SageMaker 콜백이 영영 오지 않는 경우가 있습니다. 엔드포인트가 죽거나, 알림이 유실되거나,
추론이 비정상 종료되면 작업은 `PROCESSING` 상태로 굳습니다. 이런 **좀비 작업**을 걷어내는 스케줄러가
5분마다 돕니다.

```java
@Scheduled(cron = "0 0/5 * * * *")
@SchedulerLock(
        name = "scheduleCleanUp_lock", // Redis에 저장될 락의 고유 이름
        lockAtLeastFor = "4m",         // 락을 최소한 유지할 시간 (중복 실행 방지의 핵심 방어선)
        lockAtMostFor = "10m"          // 락을 최대한 유지할 시간 (서버 다운 시 데드락 방지용)
)
public void scheduleCleanUp() { ... }
```

이 스케줄러는 단순히 상태만 바꾸는 것이 아니라 **클라이언트에게 실패 알림(SSE)까지 발송**합니다.
중복 실행이 곧 중복 알림입니다.

## 2. 증상

인스턴스를 2대로 늘리는 순간 **같은 작업에 대해 실패 알림이 두 번** 갔습니다. 사용자 화면에는 같은
실패 토스트가 연달아 뜹니다. 1대로 돌릴 때는 재현되지 않으니, 코드를 아무리 들여다봐도 원인이
보이지 않는 종류의 버그입니다.

## 3. 원인

`@Scheduled`는 **JVM 단위**로 동작합니다. 스프링은 그 인스턴스 안에서 중복을 막아 줄 뿐, 옆 인스턴스가
같은 시각에 같은 일을 하는 것은 알지 못합니다. 인스턴스가 N대면 스케줄러도 N개입니다.

ECS 롤링 배포 중에는 `maxHealthyPercent: 200` 설정 때문에 **구 태스크와 신 태스크가 동시에 떠 있는
구간**이 반드시 생깁니다. 즉 `desiredCount: 1`로 운영하더라도 배포 때마다 이 문제를 지나갑니다.

## 4. 검토한 대안

| 대안 | 기각 이유 |
| --- | --- |
| DB 유니크 제약으로 "이미 처리됨"을 막는다 | 스케줄러가 하는 일은 상태 전이와 알림 발송입니다. 상태 전이는 멱등하게 만들 수 있지만 **알림 발송은 멱등하지 않습니다.** 이미 나간 SSE 이벤트는 되돌릴 수 없습니다. |
| 리더 선출(예: 인스턴스 중 하나만 스케줄러 활성화) | 리더가 죽었을 때 새 리더를 뽑는 로직이 필요합니다. 스케줄러 하나 때문에 클러스터 멤버십을 관리하게 됩니다. ShedLock이 같은 보장을 훨씬 적은 코드로 줍니다. |
| 스케줄러를 별도 단일 태스크로 분리 | 인스턴스 하나를 스케줄러 전용으로 상시 띄워야 합니다. 5분에 한 번 도는 일에 Fargate 태스크 비용을 계속 내는 셈입니다. |
| ShedLock의 JDBC Provider | 락 조회·갱신이 5분마다 DB에 붙습니다. Redis는 이미 세션·레이트리밋·SSE Pub/Sub으로 쓰고 있고, TTL 기반 만료가 이 용도에 더 자연스럽습니다. |

## 5. 해결

Redis Provider 기반 ShedLock을 도입했습니다. 스케줄러 실행 시 `name`을 키로 Redis에 락을 만들고,
이미 같은 키가 있으면 다른 서버가 수행 중으로 판단해 건너뜁니다.

```java
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class ShedLockConfig {
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, redisProperties.shedlock().prefix());
    }
}
```

중요한 것은 도입 자체가 아니라 **두 파라미터의 의미가 완전히 다르다는 것**입니다. 둘 다 "락 시간"처럼
보이지만 막는 사고가 다릅니다.

### 5-1. `lockAtLeastFor = "4m"` — 시계 오차 방어

작업이 1초 만에 끝나도 락을 **4분 동안 강제로 유지**합니다.

A 서버와 B 서버의 시스템 시계(NTP)는 1~2초 어긋날 수 있습니다. A가 먼저 트리거되어 작업을 순식간에
끝내고 락을 풀어 버리면, 1초 뒤 트리거된 B가 "어? 락이 없네"라며 **또 실행합니다.** 좀비 작업이 없어
0.1초 만에 끝나는 날일수록 이 창이 더 잘 열립니다.

값은 **실행 주기(5분)보다 약간 짧게** 잡습니다. 주기보다 길면 다음 정상 실행까지 막아 버립니다.

### 5-2. `lockAtMostFor = "10m"` — 데드락 방어

락을 쥔 서버가 OOM이나 강제 종료로 죽으면 락을 풀어 줄 주체가 사라집니다. 이 값은 **락이 영구히 남는
것을 막는 안전장치**이고, 시간이 지나면 Redis TTL로 자동 소멸합니다.

방향이 반대라는 점이 중요합니다. `lockAtLeastFor`는 **너무 빨리 풀리는 것**을, `lockAtMostFor`는
**영원히 안 풀리는 것**을 막습니다. 그래서 `lockAtMostFor`는 최악의 실행 시간보다 **길게** 잡아야 합니다.
로직이 6분 걸리는데 이 값이 5분이면, 5분 뒤 락이 풀려 B 서버가 중복 실행합니다.

`@EnableSchedulerLock(defaultLockAtMostFor = "10m")`은 개별 `@SchedulerLock`에서 이 값이 누락됐을 때
적용되는 전역 안전장치입니다.

## 6. 검증

- **로컬 2인스턴스 재현** — 같은 Redis를 바라보는 앱 컨테이너를 두 개 띄우고 로그를 비교합니다.
  한쪽에만 `좀비 작업 정리 프로세스 가동` 로그가 찍혀야 합니다.

  ```bash
  docker compose up -d --scale app=2   # 포트 매핑은 사전에 조정 필요
  docker compose logs app | grep "좀비 작업 정리"
  ```
- **Redis 락 키 확인** — 실행 직후 락이 살아 있고, `lockAtLeastFor` 시간이 지나야 사라지는지 봅니다.

  ```bash
  docker compose exec redis redis-cli --scan --pattern 'Aetheria-Shedlock*'
  docker compose exec redis redis-cli TTL "<위에서 나온 키>"
  ```
- **정리 로직 자체** — [`AiTaskCleanupServiceTest.java`](../../src/test/java/com/serverbe/application/service/AiTaskCleanupServiceTest.java)

## 7. 남은 과제

두 건 모두 **이 문서 작성 중 코드를 대조하다 발견**한 것으로, 아직 고치지 않았습니다.

### 7-1. 스케줄러가 실제로는 한 번도 실행되지 않는다

저장소 전체에 **`@EnableScheduling`이 없습니다.** `ServerBeApplication`에는 `@SpringBootApplication`과
`@ConfigurationPropertiesScan`만 붙어 있고, 다른 어떤 설정 클래스에도 이 애노테이션이 없습니다.

```bash
$ grep -rn "EnableScheduling" --include=*.java src/
$ # (결과 없음)
```

Spring Boot는 `@EnableScheduling` 없이는 `ScheduledAnnotationBeanPostProcessor`를 등록하지 않으므로
**`@Scheduled`는 조용히 무시됩니다.** 예외도, 경고 로그도 없습니다. 즉 좀비 작업 정리는 지금 돌지 않고,
`PROCESSING`으로 굳은 작업은 영원히 남으며, `active_user_id` 슬롯을 점유한 사용자는 **새 작업을 만들 수
없는 상태로 방치**됩니다.

역설적으로 이 때문에 ShedLock 중복 실행 문제도 현재는 발생하지 않습니다. 애노테이션 한 줄을 추가하는
순간 스케줄러가 살아나므로, 그때 이 문서의 락 설정이 실제로 검증됩니다.

### 7-2. 주석과 코드의 값이 다르다

`@SchedulerLock`의 애노테이션은 `lockAtMostFor = "10m"`인데, 바로 아래 붙은 설명 주석은
`2. lockAtMostFor = "5m" (최대 잠금 시간)`이라고 적혀 있습니다. 실제 동작은 `10m`입니다.
주석이 코드보다 오래된 상태이며, 이 문서는 실제 값 기준으로 서술했습니다.
