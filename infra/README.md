# Aetheria AWS CDK

Aetheria 백엔드(Java 17 / Spring Boot 3.5)를 AWS 에 배포하기 위한 인프라 코드다.
설계 근거는 [`CLAUDE.md`](./CLAUDE.md), 합성 결과의 구조도는 [`docs/architecture.md`](./docs/architecture.md) 에 있고,
이 문서는 그 설계를 실제로 어떻게 돌리는지를 다룬다.

백엔드와 같은 저장소에 있으며 **이 문서의 모든 명령은 `infra/` 에서 실행한다.**
앱 배포는 저장소 루트의 `.github/workflows/deploy.yml` 이, 인프라 배포는 여기서 `cdk deploy` 가 담당한다.
같은 저장소에 있어도 트리거는 분리되어 있다 — `deploy.yml` 의 `paths-ignore` 가 `infra/**` 를 빼고,
`infra-ci.yml` 은 반대로 `infra/**` 만 본다.

## 스택 구성

| 스택 | 내용 |
| --- | --- |
| `AetheriaNetworkStack` | VPC(2 AZ), NAT Gateway 1개, public/app/data 서브넷, 보안그룹 4종, S3 게이트웨이 엔드포인트 |
| `AetheriaDataStack` | RDS MySQL 8.0(`db.t4g.micro`, 단일 AZ), ElastiCache Redis(`cache.t4g.micro`, 단일 노드), 앱 시크릿 |
| `AetheriaAppStack` | ECR, 비동기 파이프라인용 S3 버킷 + SNS 토픽 2개 + SQS(+DLQ), ECS Fargate 서비스, ALB, IAM 역할 |
| `AetheriaCicdStack` | GitHub Actions OIDC 공급자 + 배포 역할 (`githubOwner`/`githubRepo` context 가 있을 때만 생성) |

보안그룹은 규칙 방향까지 전부 `AetheriaNetworkStack` 이 소유한다. ALB SG 를 앱 스택에서 만들고
DB SG(데이터 스택)에 규칙을 추가하면 스택 간 순환 참조가 생겨 합성이 깨지기 때문이다.

트래픽 경로는 `인터넷 → ALB(80) → Fargate(8080) → RDS(3306) / Redis(6379)` 이고,
각 단계는 앞 단계의 보안그룹에서 오는 트래픽만 받는다. 외부 API(카카오 로그인, 구글 OAuth,
카카오 지오코딩) 호출은 private 서브넷 → NAT Gateway 로 나간다.

## 배포 순서

```bash
npm ci                            # package-lock.json 을 그대로 따른다
npx cdk bootstrap                 # 계정/리전당 최초 1회
npx cdk deploy --all              # 3개 스택 (CI/CD 스택 제외)
```

첫 배포 시 ECR 은 비어 있다. 그래서 `imageTag` context 가 없으면 8080 을 듣는 **플레이스홀더
컨테이너**(nginx)로 서비스를 띄운다. 이게 없으면 태스크가 이미지를 받지 못해 서비스가 끝내
안정화되지 못하고 스택 전체가 롤백된다. 인프라를 먼저 세우고 실제 이미지는 CI 가 밀어 넣는 순서다.

이어서:

1. **시크릿 채우기** — `aetheria/app` 시크릿의 카카오/구글 자격증명을 콘솔이나 CLI 로 채운다.
   `JWT_SECRET` 은 CDK 가 무작위로 생성해 두었으므로 건드릴 필요 없다.
   ```bash
   aws secretsmanager put-secret-value --secret-id aetheria/app --secret-string '{...}'
   ```
2. **OAuth 리디렉트 URI 등록** — `LoadBalancerDns` 출력값을 카카오/구글 콘솔에 등록한다.
3. **CI/CD 역할 생성**
   ```bash
   npx cdk deploy AetheriaCicdStack -c githubOwner=<소유자> -c githubRepo=<저장소>
   ```
   출력된 `DeployRoleArn` 을 이 저장소의 Actions Secret `AWS_DEPLOY_ROLE_ARN` 에 등록한다.
   `githubRepo` 는 백엔드 저장소 이름(`Server-BE`)이다 — 워크플로우가 도는 저장소가 곧 OIDC 주체이기 때문이다.
4. 배포 워크플로우를 돌리면 테스트 → 이미지 빌드 → ECR push → 새 태스크 정의 등록 → 롤링 배포가 이어진다.
   워크플로우는 이미 `.github/workflows/deploy.yml` 에 있으므로 따로 설치할 것이 없다.
   다만 **자동 트리거(`master` 푸시)는 현재 꺼져 있다** — Actions 탭에서 수동 실행(`workflow_dispatch`)만 가능하다.
   다시 켜려면 `deploy.yml` 의 `on:` 안에 주석 처리된 `push:` 블록을 되살리면 된다.

이후 **코드 배포는 GitHub Actions 가, 인프라 변경은 `cdk deploy` 가** 담당한다. 둘은 분리되어 있다.

## Context 파라미터

전부 선택 사항이며 `cdk.json` 의 `context` 나 `-c key=value` 로 준다.

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `imageTag` | (없음) | 배포할 이미지 태그(git sha). 없으면 플레이스홀더 컨테이너로 부트스트랩 |
| `taskCpu` / `taskMemory` | `512` / `1024` | 하한이다. 0.25 vCPU/512MB 로는 기동이 유예 시간을 넘긴다 |
| `desiredCount` | `1` | 이중화하려면 `2` |
| `maxCapacity` | `2` | CPU 70% 타깃 오토스케일링 상한 |
| `dbInstanceClass` | `t4g.micro` | RDS 인스턴스 클래스 |
| `dbAllocatedStorage` | `20` | GB. 자동 확장 상한은 이 값의 5배 |
| `redisNodeType` | `cache.t4g.micro` | |
| `retainData` | `false` | `true` 면 RDS/S3/시크릿을 스택 삭제 시 보존하고 삭제 방지를 켠다 |
| `sagemakerEndpointName` | (없음) | 주면 그 ARN 에만 `sagemaker:InvokeEndpoint(Async)` 를 허용 |
| `rateLimitUserCapacity` / `rateLimitUserRefillRate` | `10` / `5` | 백엔드 `rate-limit.user.*` 로 주입 |
| `rateLimitIpCapacity` / `rateLimitIpRefillRate` | `20` / `10` | 백엔드 `rate-limit.ip.*` 로 주입 |
| `frontendOrigin` / `developOrigin` | (없음) | CORS 허용 오리진. 미지정 시 ALB 주소로 채운다 |
| `certificateArn` / `domainName` | (없음) | 주면 443 리스너 + 80→443 리다이렉트로 전환 |
| `githubOwner` / `githubRepo` | (없음) | 없으면 `AetheriaCicdStack` 을 만들지 않음 |
| `githubBranch` | `master` | OIDC 신뢰 정책이 허용할 브랜치 |
| `createGithubOidcProvider` | `true` | 계정에 GitHub OIDC 공급자가 이미 있으면 `false` |
| `enableInterfaceEndpoints` | `false` | ECR/Logs/SecretsManager/SQS/SageMaker 인터페이스 엔드포인트 생성 |

정해지지 않은 값은 전부 기능만 비활성화되고 배포는 그대로 진행된다.
즉 SageMaker 엔드포인트나 도메인이 아직 없어도 `cdk deploy --all` 이 통과한다.

## 설계 판단 몇 가지

**NAT Gateway 는 유지하고 인터페이스 엔드포인트는 기본으로 끈다.**
카카오/구글 API 호출이 필수라 NAT 는 VPC 엔드포인트로 대체할 수 없다. 반면 인터페이스
엔드포인트는 개당 월 $7 수준의 고정비라 5~6개를 켜면 NAT 자체 비용에 근접한다. 대신 **비용이
0원인 S3 게이트웨이 엔드포인트는 항상 만든다** — ECR 이미지 레이어가 실제로는 S3 에서 내려오므로
NAT 데이터 처리 비용의 가장 큰 몫이 여기서 빠진다. 트래픽이 커지면
`-c enableInterfaceEndpoints=true` 로 전환한다.

**헬스체크 유예 시간(180초)이 없으면 배포가 무한 반복된다.**
Spring Boot 기동에 더해 Flyway 마이그레이션까지 끝나야 readiness 가 올라온다. 유예가 없으면
ALB 가 기동 중인 태스크를 unhealthy 로 판정해 죽이고, ECS 가 다시 띄우기를 반복한다.
서킷 브레이커(`rollback: true`)도 함께 켜서 나쁜 이미지가 올라가면 직전 리비전으로 자동 복귀하게 했다.

**ALB 헬스체크는 `/actuator/health` 가 아니라 `/actuator/health/alb` 를 본다.**
기본 엔드포인트는 서킷브레이커·db·redis 인디케이터를 모두 집계한다. 카카오 API 가 느려져 서킷이
열리는 것만으로 503 이 되고, ALB 가 멀쩡한 태스크를 죽인다. 외부 API 장애가 서비스 전체 장애로
번지는 경로다. 백엔드에 `readinessState` 하나만 보는 전용 헬스 그룹을 두고 그쪽을 가리킨다.

**JVM 힙을 컨테이너 메모리에 맞춰 못박았다.**
`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` 이 없으면 JVM 이 기본 비율로 힙을 잡아 작은
태스크에서 OOM-kill 되기 쉽다. 그래도 부족하면 `taskCpu`/`taskMemory` 를 올린다.

**시크릿 값은 코드에도 git 에도 없다.**
DB 비밀번호는 RDS 가 Secrets Manager 에 생성하고, 외부 연동 자격증명은 빈 자리만 만들어 둔 뒤
사람이 채운다. 컨테이너에는 값이 아니라 시크릿 참조로 주입된다.

**IAM 은 리소스 단위로 좁혔다.**
`sagemaker:InvokeEndpoint(Async)` 는 지정한 엔드포인트 ARN 하나로, GitHub Actions 의 `iam:PassRole` 은
태스크 역할 2개 + `ecs-tasks.amazonaws.com` 조건으로, OIDC 신뢰 정책은 특정 저장소의 특정
브랜치(`repo:owner/repo:ref:refs/heads/master`)로 제한된다. 와일드카드는 리소스 스코프를 지원하지
않는 API(`ecr:GetAuthorizationToken`, `ecs:RegisterTaskDefinition`)에만 쓴다.

**환경변수 이름은 백엔드 `application.yml` 기준으로 맞춘다.**
백엔드 플레이스홀더에는 기본값이 없어, 이름 하나만 어긋나도 컨테이너가
`Could not resolve placeholder` 로 기동조차 하지 못한다. 그런데 그 실패는 `cdk synth` 도
`cdk deploy` 도 잡아 주지 않고 ECS 태스크가 죽고 나서야 드러난다. 그래서
`test/aetheria-cdk.test.ts` 의 "백엔드 계약" 블록이 주입 이름 집합을 단언으로 고정한다.

두 가지가 특히 헷갈린다. 백엔드는 WebFlux 를 쓰지만 영속성은 JPA(JDBC) 라 **R2DBC URL 은 넣지 않는다**
(의존성 자체가 없다). 그리고 Redis 커넥션은 `spring.data.redis.*` 가 아니라 백엔드의 커스텀
`redis.*` 프리픽스에서 만들어져서, **`SPRING_DATA_REDIS_HOST` 로는 연결되지 않는다** — `REDIS_HOST` 여야 한다.

**SageMaker 콜백은 SNS 를 거쳐야 한다.**
비동기 추론의 `NotificationConfig` 는 SNS 토픽만 받는다. SQS 를 직접 지정할 수 없어 토픽 2개
(성공/실패)를 만들고 그 토픽이 알림 큐를 구독하게 했다. 구독은 **raw message delivery** 여야 한다.
아니면 SNS 봉투가 씌워져 백엔드의 역직렬화가 전부 실패하고 모든 메시지가 DLQ 로 빠진다.

## 대략적인 비용

서울 리전 기준 유휴 상태 월 요금의 개략치다. 실제 청구는 트래픽과 사용량에 따라 달라진다.

| 항목 | 월 대략 |
| --- | --- |
| NAT Gateway (1개) | ~$43 + 데이터 처리 |
| ALB | ~$17 + LCU |
| Fargate 0.5 vCPU / 1GB × 1 | ~$20 |
| RDS `db.t4g.micro` + 20GB gp3 | ~$22 |
| ElastiCache `cache.t4g.micro` | ~$15 |
| ECR / S3 / SNS / SQS / CloudWatch Logs | ~$1~5 |
| **합계** | **월 $115 ~ $125 선** |

가장 큰 항목은 NAT Gateway 와 ALB 다. 둘 다 CLAUDE.md 에서 유지하기로 한 구성요소다.

## 검증

```bash
npx tsc --noEmit  # 타입 체크
npm test          # 25개 assertion (스펙, 무중단 배포, IAM 스코프, 조건부 분기, 백엔드 계약)
npx cdk synth --all
npx cdk diff --all
```

앞의 두 개는 `.github/workflows/infra-ci.yml` 이 `infra/**` 를 건드린 PR 마다 자동으로 돌린다.

테스트는 `cdk.json` 의 feature flag 를 직접 읽어 실제 `cdk synth` 와 같은 조건에서 합성한다.
`new cdk.App()` 은 `cdk.json` 을 읽지 않기 때문이다(CLI 가 환경변수로 넘겨주는 값이다).

## 남은 작업

- SageMaker 엔드포인트를 만들 때 `S3OutputPath` 를 `InferenceOutputS3Path` 출력값으로,
  `NotificationConfig` 의 성공/실패 토픽을 `InferenceSuccessTopicArn` / `InferenceErrorTopicArn` 으로 지정한다.
  그 뒤 `-c sagemakerEndpointName=<이름>` 으로 재배포해 호출 권한을 붙인다.
- `aetheria/app` 시크릿을 채운다. 특히 `ENCRYPTION_SECRET_KEY_V1/V2` 는 PII 컬럼의 복호화 키라
  **기존 데이터를 이어 쓸 거면 기존 키를 그대로** 넣어야 하고, 빈 문자열이면 기동에 실패한다.
- 도메인과 ACM 인증서가 준비되면 `-c certificateArn=... -c domainName=...` 으로 HTTPS 전환한다.
  (카카오/구글 OAuth 리디렉트 URI 재등록 필요) — **구글 OAuth 는 localhost 가 아닌 리디렉트 URI 에
  HTTPS 를 강제하므로, 그 전까지 구글 로그인은 동작하지 않는다.** 카카오는 http 로도 된다.
- 운영 전환 시 `-c retainData=true` 로 데이터 리소스 보호를 켠다.
