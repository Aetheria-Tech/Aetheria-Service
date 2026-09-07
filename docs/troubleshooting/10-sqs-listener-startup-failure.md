# 10. 배포하기 전에 잡은 기동 실패 — 자격증명이 없으면 뜨지 않는 SQS 리스너

> 요약 · [README — 10. 배포하기 전에 잡은 기동 실패](../../README.md#10-배포하기-전에-잡은-기동-실패--자격증명이-없으면-뜨지-않는-sqs-리스너)
> 근거 · [`AiNotificationSqsListener.java`](../../src/main/java/com/serverbe/adapter/in/messaging/AiNotificationSqsListener.java) · [`application.yml`](../../src/main/resources/application.yml) · [`docker-compose.yml`](../../docker-compose.yml) · [`build.gradle`](../../build.gradle)

## 1. 상황 — 왜 배포 전에 띄워 봤나

ECS 배포 파이프라인을 붙이기 직전이었습니다. 당시 `.github/workflows/deploy.yml`은 `master` 푸시 한 번에
**테스트 → 이미지 빌드 → ECR push → ECS 롤링 배포**까지 자동으로 갔습니다.
(자동 트리거는 이후 껐고, 지금은 `workflow_dispatch` 수동 실행만 열려 있습니다.)

여기서 기동이 실패하면 실패 지점이 **CI 로그와 CloudWatch로 흩어집니다.** ECS는 헬스체크 유예(180초)
안에 readiness가 올라오지 않으면 태스크를 죽이고, 배포 서킷 브레이커가 롤백합니다. 로그를 찾을 때쯤
태스크는 이미 사라져 있습니다.

그래서 **운영에 올라갈 것과 똑같은 `Dockerfile` 이미지**를 로컬에서 먼저 띄웠습니다.

## 2. 증상

컨테이너는 Flyway 마이그레이션까지 정상적으로 마쳤습니다. 그리고 **컨텍스트 refresh의 마지막 단계에서**
죽었습니다.

```
Caused by: io.awspring.cloud.sqs.QueueAttributesResolvingException:
    Error resolving attributes for queue ai-generation-notification-queue
    with strategy CREATE and queueAttributesNames []
    at io.awspring.cloud.sqs.QueueAttributesResolver.wrapException(QueueAttributesResolver.java:90)
    ...
Caused by: software.amazon.awssdk.core.exception.SdkClientException:
    Unable to load credentials from any of the providers in the chain AwsCredentialsProviderChain(
      credentialsProviders=[SystemPropertyCredentialsProvider(), EnvironmentVariableCredentialsProvider(),
      WebIdentityTokenCredentialsProvider(), ProfileCredentialsProvider(...),
      ContainerCredentialsProvider(), InstanceProfileCredentialsProvider()])
```

스택트레이스의 상단은 `DefaultLifecycleProcessor.doStart` → `AbstractApplicationContext.finishRefresh`
입니다. 즉 **빈 생성은 전부 끝났고, 라이프사이클 시작 단계에서 죽은 것**입니다.

### 관측 차이 — 컨테이너는 즉시 실패, 개발 PC는 무한 대기

같은 원인이 환경에 따라 전혀 다른 모습으로 나타났습니다.

| 환경 | 증상 |
| --- | --- |
| 리눅스 컨테이너 (Docker) | 자격증명 체인이 **몇 초 만에** 예외로 떨어지고 기동 실패 |
| Windows 개발 PC (`gradlew integrationTest`) | **15분 넘게 로그 한 줄 없이 멈춤** |

기동 실패는 스택트레이스가 남지만, 무한 대기는 아무것도 남기지 않습니다.
**후자가 훨씬 진단하기 어렵습니다.** 컨테이너에서 먼저 확인하지 않았다면 "Gradle이 느리네" 정도로
넘겼을 가능성이 큽니다.

## 3. 원인

```java
@Component
public class AiNotificationSqsListener {
    ...
    @Transactional
    @SqsListener(QUEUE_NAME_PROPERTY)
    public void receiveAiTaskNotification(SageMakerNotificationDto message) { ... }
```

이 클래스에는 **`@Profile`도 `@ConditionalOnProperty`도 없습니다.** 즉 모든 프로파일에서 활성화됩니다.

`spring-cloud-aws-starter-sqs`는 리스너 컨테이너를 `SmartLifecycle`로 기동하면서
**실제 SQS에 `GetQueueUrl`을 호출**합니다(`strategy CREATE`이므로 없으면 만들려고까지 합니다).
자격증명이 없으면 여기서 예외가 나고, `SmartLifecycle` 시작 실패는 **컨텍스트 refresh 자체를 중단**시킵니다.

`local` 프로파일은 AWS 의존을 가려 주도록 설계되어 있습니다.

| 포트 | local 프로파일의 대역 |
| --- | --- |
| `S3AiInputPort` | `FakeS3Adapter` (`@Primary @Profile({"local","test","dev"})`) |
| `S3AiOutputPort` | `MockS3AiOutputAdapter` (`@Primary @Profile({"local","dev"})`) |
| `SageMakerAsyncPort` | `FakeSageMakerAdapter` (`@Primary @Profile({"local","test"})`) |
| **SQS 리스너** | **없음 ← 그물에서 빠져 있었다** |

`AwsConfig`의 `S3Client`·`SageMakerRuntimeClient` 빈은 문제가 되지 않습니다. AWS SDK v2의
`DefaultCredentialsProvider`는 **빌드 시점이 아니라 첫 호출 시점에** 자격증명을 해석하기 때문입니다.
오직 SQS 리스너만 기동 중에 실제 API를 부릅니다.

## 4. 검토한 대안

| 대안 | 기각 이유 |
| --- | --- |
| 클래스에 `@Profile("prod")` | **당시엔 쓸 수 없었습니다.** [`AiTestController`](../../src/main/java/com/serverbe/adapter/in/web/AiTestController.java)가 바로 이 빈을 주입받아 로컬에서 AI 파이프라인을 시뮬레이션했기 때문입니다. 리스너 빈이 사라지면 그 도구도 함께 죽습니다. (지금은 그 결합이 없습니다. 그래도 결론은 같습니다 — 바로 아래 상자를 보세요.) |
| 가짜 AWS 자격증명을 환경변수로 준다 | 자격증명 **해석**은 통과하지만 `GetQueueUrl`이 실제 AWS로 나가 인증 실패합니다. 문제를 한 단계 미룰 뿐입니다. |
| LocalStack / ElasticMQ를 로컬 스택에 추가 | 가장 충실한 재현이지만, 로컬에서 앱 하나 띄우는 데 컨테이너와 큐 생성 스크립트가 더 필요해집니다. 이번 목적(이미지 기동 검증)에 비해 과합니다. 실제 SQS 소비 경로를 검증해야 할 때 다시 꺼낼 카드입니다. |
| 리스너 메서드를 조건부로 등록하는 커스텀 설정 | `@SqsListener`는 애노테이션 기반이라 메서드 단위 조건부 등록이 자연스럽지 않습니다. 프레임워크가 이미 제공하는 스위치가 있습니다. |
| CI에서만 SQS를 끄고 로컬은 그대로 | 개발 PC에서 15분 멈추는 문제가 남습니다. 그리고 "왜 CI만 다르지?"라는 질문을 매번 다시 하게 됩니다. |

## 5. 해결

리스너 빈은 살려 두고 **폴링 컨테이너만** 끕니다.

```yaml
spring:
  cloud:
    aws:
      sqs:
        # AiNotificationSqsListener 의 @SqsListener 는 컨텍스트 기동 중 실제 SQS 에 큐 URL 을 조회한다.
        # 자격증명이 없는 환경(로컬 도커, CI)에서는 이 단계에서 기동 자체가 중단된다.
        # false 로 두면 @SqsListener 를 처리하는 BeanPostProcessor 가 등록되지 않아 폴링만 사라지고,
        # 리스너 빈은 남아 AiTestController 의 mock-sqs-receive 시뮬레이션은 그대로 동작한다.
        # 운영(ECS)은 태스크 역할로 자격증명이 있으므로 기본값 true 를 그대로 쓴다.
        enabled: ${AWS_SQS_ENABLED:true}
```

> 위 표의 첫 줄은 그 뒤 사정이 달라졌습니다. `AiTestController`는 이제 리스너가 아니라
> `HandleAiNotificationUseCase`를 부르므로, 리스너 빈이 사라져도 시뮬레이션은 살아남습니다.
> **그럼에도 아래 5장의 선택을 되돌리지 않았습니다** — `@Profile`은 "로컬에서 빈을 지우는" 해결이고,
> 우리가 끄고 싶은 것은 빈이 아니라 **네트워크를 타는 폴링**이기 때문입니다. 프로파일로 막으면 로컬에서
> 존재하지 않는 빈을 두고 통합 테스트를 짜게 됩니다. 결합이 사라졌다고 해서 결론이 바뀌지는 않는,
> **기각 이유와 기각 결론이 따로 서 있는** 경우입니다.

### 왜 이 스위치가 정확히 맞는가

`spring.cloud.aws.sqs.enabled=false`이면 SQS 자동 구성이 통째로 비활성화되고, 그 안에서 등록되던
**`@SqsListener` 처리용 BeanPostProcessor도 등록되지 않습니다.** 결과적으로 애노테이션은 그냥 무시되고,
`@Component`로 선언된 리스너 빈은 **평범한 스프링 빈으로 남습니다.**

즉 사라지는 것은 **네트워크를 타는 폴링뿐**이고, 비즈니스 로직은 그대로 테스트할 수 있습니다.
`AiTestController`는 큐를 거치지 않고 유스케이스를 직접 호출하므로 영향받지 않습니다.

```java
// AiTestController.java — 큐를 건너뛰고 유스케이스로 바로 들어간다
handleAiNotificationUseCase.handleNotification(
        new AiNotificationCommand(taskId, completed, failureReason));
```

> 이 줄은 원래 `sqsListener.receiveAiTaskNotification(dummyMessage)`였습니다. 웹 어댑터가 메시징
> 어댑터를 주입받고 있었던 셈인데, 지금은 두 진입점이 같은 포트 앞에서 만납니다.
> 배경은 [12번 문서 §7](12-why-not-kafka.md#7-남은-과제--무엇을-정리했고-무엇을-남겼나)에 있습니다.

### 기본값을 `true`로 둔 것이 핵심이다

`${AWS_SQS_ENABLED:true}` — **아무것도 주입하지 않으면 켜집니다.**

- 운영(ECS)은 태스크 역할로 자격증명을 받으므로 **기존 동작 그대로**입니다.
- CDK가 주입하는 환경변수 계약([`infra/docs/architecture.md`](../../infra/docs/architecture.md))도
  바뀌지 않습니다. 태스크 정의에 새 변수를 추가할 필요가 없습니다.
- **끄는 쪽이 명시적인 선택**이 되므로, 운영에서 실수로 조용히 꺼지는 일이 생기지 않습니다.

끄는 곳은 두 군데뿐입니다.

```yaml
# docker-compose.yml — 로컬 컨테이너
AWS_SQS_ENABLED: "false"
```

```groovy
// build.gradle — test / integrationTest 태스크
environment 'AWS_SQS_ENABLED', 'false'
```

Gradle 쪽은 **셸 환경변수로는 전달되지 않았습니다.** 태스크 정의에 `environment`로 박아야
테스트 JVM이 확실히 받습니다. 이렇게 두면 누구든 저장소를 클론한 직후 AWS 계정 없이
`./gradlew integrationTest`를 돌릴 수 있습니다.

## 6. 같은 검증에서 함께 나온 것

이미지를 실제로 띄워 보지 않았다면 몰랐을 것들입니다.

### 6-1. `mysql:8` 태그가 8.4를 끌고 온다

```
org.flywaydb.core.internal.database.base.Database :
  Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway
  and support has not been tested. The latest supported version of MySQL is 8.1.
```

운영 RDS는 `MysqlEngineVersion.VER_8_0`입니다. 로컬만 8.4면 **로컬에서 통과한 마이그레이션이 운영에서
다르게 동작할 수 있습니다.** `docker-compose.yml`을 `mysql:8.0`으로 고정했습니다.

### 6-2. 스키마는 Flyway가 만들지 않는다

마이그레이션 스크립트 어디에도 `CREATE DATABASE`가 없습니다. **빈 스키마가 먼저 있어야** 합니다.

```
All configured schemas are empty; baseline operation skipped.
Migrating schema `webflux` to version "1 - init baseline"
```

로컬에서는 compose의 `MYSQL_DATABASE`가, 운영에서는 RDS의 `databaseName`이 그 역할을 합니다.

### 6-3. 컨테이너에는 `.env`가 없다

`.dockerignore`가 `.env`를 제외합니다. **운영 시크릿이 이미지에 구워지면 안 되기 때문**입니다.
그런데 `application.yml`의 플레이스홀더 중 **기본값이 없는 것이 29개**라, 하나라도 빠지면
`Could not resolve placeholder`로 기동이 실패합니다.

로컬은 compose의 `env_file`이, 운영은 ECS 태스크 정의가 채웁니다. 이 대응이 어긋나면 배포가 실패하므로,
CDK 테스트의 "백엔드 계약" 블록이 이 목록을 강제합니다.

## 7. 검증

빈 볼륨에서 한 번에 기동되는지가 최종 기준입니다.

```bash
docker compose down -v && docker compose up -d --build
```

| 확인 | 실제 결과 |
| --- | --- |
| Flyway 마이그레이션 | `Successfully applied 5 migrations to schema webflux, now at version v5` |
| Hibernate `validate` | 통과 (기동 로그에 예외 없음) |
| 기동 시간 | 약 10~13초 |
| `curl -o /dev/null -w "%{http_code}" localhost:8080/actuator/health/alb` | `200` |
| `/actuator/health` | `db` · `redis` · `circuitBreakers` 전부 `UP` |
| `docker compose exec app id` | `uid=999(spring)` — 비루트 |
| `docker compose restart app` 후 재확인 | Flyway 재실행 없이 `200` |

```bash
# Flyway 적용 이력
docker compose exec mysql mysql -uroot -pletmein webflux \
  -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

# 테스트
./gradlew test
./gradlew integrationTest --tests "com.serverbe.ServerBeApplicationTests"
```

## 8. 남은 과제

- **실제 SQS 소비 경로는 로컬에서 검증되지 않습니다.** 지금 로컬에서 도는 것은 유스케이스 직접 호출
  (`mock-sqs-receive`)이지, 메시지 역직렬화·가시성 타임아웃·DLQ 이동을 포함한 전체 경로가 아닙니다.
  이 경로까지 검증하려면 LocalStack이나 ElasticMQ가 필요합니다. 리스너가 얇아진 지금은 **건너뛰는
  구간이 더 명확해졌을 뿐** 여전히 건너뜁니다 — `SageMakerNotificationDto` 역직렬화와 Task ID 판정이
  그 구간이고, 그래서 그 부분만은 단위 테스트로 따로 덮어 두었습니다.
- `strategy CREATE`는 큐가 없으면 **만들려고 시도**합니다. 운영에서 큐 이름 오타가 나면 조용히 빈 큐가
  생겨 메시지를 영영 받지 못하는 상황이 가능합니다. 조회 전용 전략으로 바꾸는 편이 안전합니다.
