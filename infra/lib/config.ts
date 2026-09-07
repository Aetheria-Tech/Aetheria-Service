import * as cdk from 'aws-cdk-lib/core';
import { Construct } from 'constructs';

/**
 * cdk.json / `-c key=value` 로 주입되는 배포 파라미터를 한 곳에서 해석한다.
 *
 * 각 스택이 tryGetContext 를 직접 호출하지 않게 해서 기본값이 여기에만 존재하도록 한다.
 * CLAUDE.md 의 "비용 최소화 우선" 기조에 맞춰 기본값은 전부 최소 스펙이고,
 * 아직 정해지지 않은 값(SageMaker 엔드포인트, 도메인, GitHub 저장소)은
 * undefined 를 허용해 해당 기능만 비활성화되고 배포 자체는 막히지 않게 한다.
 */
export interface AetheriaConfig {
  /** 배포할 컨테이너 이미지 태그(git sha). 없으면 플레이스홀더 이미지로 부트스트랩한다. */
  readonly imageTag?: string;

  /**
   * Fargate 태스크 스펙.
   * WebFlux + JPA + Querydsl + Redis + AWS SDK v2 + Flyway 가 한 프로세스에 올라가므로
   * 0.25 vCPU / 512MB 로는 기동 자체가 유예 시간을 넘긴다. 0.5 vCPU / 1GB 를 하한으로 본다.
   */
  readonly taskCpu: number;
  readonly taskMemory: number;
  readonly desiredCount: number;
  readonly maxCapacity: number;

  readonly dbInstanceClass: string;
  readonly dbAllocatedStorage: number;
  readonly redisNodeType: string;

  /** 운영 스택 보호. 개발 중에는 false 로 두어야 스택 삭제가 막히지 않는다. */
  readonly retainData: boolean;

  /** SageMaker 비동기 추론 엔드포인트 이름. 주어지면 그 ARN 으로만 호출을 허용한다. */
  readonly sagemakerEndpointName?: string;

  /**
   * 백엔드 rate-limit.* 프로퍼티에 그대로 주입되는 토큰 버킷 파라미터.
   * capacity 는 순간 버스트 허용량, refillRate 는 초당 리필 속도다.
   */
  readonly rateLimitUserCapacity: number;
  readonly rateLimitUserRefillRate: number;
  readonly rateLimitIpCapacity: number;
  readonly rateLimitIpRefillRate: number;

  /**
   * CORS 허용 오리진. 백엔드는 allowCredentials(true) 라 와일드카드를 쓸 수 없어
   * 구체적인 오리진이 필요하다. 미지정 시 ALB 주소(또는 domainName)로 채운다.
   */
  readonly frontendOrigin?: string;
  readonly developOrigin?: string;

  /** ALB HTTPS. 두 값이 모두 있어야 443 리스너 + 80→443 리다이렉트가 켜진다. */
  readonly domainName?: string;
  readonly certificateArn?: string;

  /** GitHub Actions OIDC. owner/repo 가 없으면 CI/CD 스택 자체를 만들지 않는다. */
  readonly githubOwner?: string;
  readonly githubRepo?: string;
  readonly githubBranch: string;
  readonly createGithubOidcProvider: boolean;

  /**
   * ECR/Logs/SecretsManager/SQS 용 인터페이스 VPC 엔드포인트 생성 여부.
   * 개당 월 ~$7 고정비라 4개면 NAT Gateway 자체 비용에 근접한다. 트래픽이 커지기 전에는 끄는 편이 싸다.
   */
  readonly enableInterfaceEndpoints: boolean;
}

function str(scope: Construct, key: string): string | undefined {
  const v = scope.node.tryGetContext(key);
  if (v === undefined || v === null || v === '') return undefined;
  return String(v);
}

function num(scope: Construct, key: string, fallback: number): number {
  const v = str(scope, key);
  if (v === undefined) return fallback;
  const n = Number(v);
  if (!Number.isFinite(n)) {
    throw new Error(`context "${key}" 는 숫자여야 합니다. 받은 값: ${v}`);
  }
  return n;
}

function bool(scope: Construct, key: string, fallback: boolean): boolean {
  const v = str(scope, key);
  if (v === undefined) return fallback;
  return v === 'true' || v === '1';
}

export function loadConfig(scope: Construct): AetheriaConfig {
  const certificateArn = str(scope, 'certificateArn');
  const domainName = str(scope, 'domainName');

  if (certificateArn && !domainName) {
    // 인증서만 있고 도메인이 없으면 HTTPS 리스너는 뜨지만 아무도 그 이름으로 접근할 수 없다.
    cdk.Annotations.of(scope).addWarning(
      'certificateArn 이 주어졌지만 domainName 이 없습니다. HTTPS 리스너는 생성되지만 Route53 안내는 생략됩니다.',
    );
  }

  return {
    imageTag: str(scope, 'imageTag'),

    taskCpu: num(scope, 'taskCpu', 512),
    taskMemory: num(scope, 'taskMemory', 1024),
    desiredCount: num(scope, 'desiredCount', 1),
    maxCapacity: num(scope, 'maxCapacity', 2),

    dbInstanceClass: str(scope, 'dbInstanceClass') ?? 't4g.micro',
    dbAllocatedStorage: num(scope, 'dbAllocatedStorage', 20),
    redisNodeType: str(scope, 'redisNodeType') ?? 'cache.t4g.micro',

    retainData: bool(scope, 'retainData', false),

    sagemakerEndpointName: str(scope, 'sagemakerEndpointName'),

    rateLimitUserCapacity: num(scope, 'rateLimitUserCapacity', 10),
    rateLimitUserRefillRate: num(scope, 'rateLimitUserRefillRate', 5),
    rateLimitIpCapacity: num(scope, 'rateLimitIpCapacity', 20),
    rateLimitIpRefillRate: num(scope, 'rateLimitIpRefillRate', 10),

    frontendOrigin: str(scope, 'frontendOrigin'),
    developOrigin: str(scope, 'developOrigin'),

    domainName,
    certificateArn,

    githubOwner: str(scope, 'githubOwner'),
    githubRepo: str(scope, 'githubRepo'),
    githubBranch: str(scope, 'githubBranch') ?? 'master',
    createGithubOidcProvider: bool(scope, 'createGithubOidcProvider', true),

    enableInterfaceEndpoints: bool(scope, 'enableInterfaceEndpoints', false),
  };
}
