import * as cdk from 'aws-cdk-lib/core';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { loadConfig } from '../lib/config';
import { AetheriaNetworkStack } from '../lib/network-stack';
import { AetheriaDataStack } from '../lib/data-stack';
import { AetheriaAppStack } from '../lib/app-stack';
import { AetheriaCicdStack } from '../lib/cicd-stack';

const env = { account: '123456789012', region: 'ap-northeast-2' };

// 테스트에서 `new cdk.App()` 은 cdk.json 을 읽지 않는다(CLI 가 환경변수로 넘겨주는 값이라).
// feature flag 가 빠지면 합성 결과가 실제 배포와 달라지므로(예: ARN 이 문자열 대신 Fn::Join 이 된다)
// 여기서 직접 읽어 넣어 준다.
const cdkJsonContext: Record<string, unknown> = require('../cdk.json').context ?? {};

/** 주어진 context 로 전체 앱을 합성하고 각 스택의 Template 을 돌려준다. */
function synth(context: Record<string, string> = {}) {
  const app = new cdk.App({ context: { ...cdkJsonContext, ...context } });
  const config = loadConfig(app);

  const network = new AetheriaNetworkStack(app, 'Net', { env, config });
  const data = new AetheriaDataStack(app, 'Data', {
    env,
    config,
    vpc: network.vpc,
    databaseSecurityGroup: network.databaseSecurityGroup,
    redisSecurityGroup: network.redisSecurityGroup,
  });
  const application = new AetheriaAppStack(app, 'App', {
    env,
    config,
    vpc: network.vpc,
    albSecurityGroup: network.albSecurityGroup,
    appSecurityGroup: network.appSecurityGroup,
    database: data.database,
    databaseSecret: data.databaseSecret,
    databaseName: data.databaseName,
    redisEndpointAddress: data.redisEndpointAddress,
    redisEndpointPort: data.redisEndpointPort,
    appSecret: data.appSecret,
  });

  const cicd =
    config.githubOwner && config.githubRepo
      ? new AetheriaCicdStack(app, 'Cicd', {
          env,
          config,
          repository: application.repository,
          cluster: application.cluster,
          service: application.service,
          taskRole: application.taskRole,
          executionRole: application.executionRole,
        })
      : undefined;

  return {
    network: Template.fromStack(network),
    data: Template.fromStack(data),
    app: Template.fromStack(application),
    cicd: cicd ? Template.fromStack(cicd) : undefined,
  };
}

describe('네트워크', () => {
  test('NAT Gateway 는 비용 때문에 1개만 만든다', () => {
    synth().network.resourceCountIs('AWS::EC2::NatGateway', 1);
  });

  test('S3 게이트웨이 엔드포인트는 항상 생성하고, 인터페이스 엔드포인트는 기본적으로 만들지 않는다', () => {
    const t = synth().network;
    t.hasResourceProperties('AWS::EC2::VPCEndpoint', {
      VpcEndpointType: 'Gateway',
    });
    const endpoints = t.findResources('AWS::EC2::VPCEndpoint', {
      Properties: { VpcEndpointType: 'Interface' },
    });
    expect(Object.keys(endpoints)).toHaveLength(0);
  });

  test('enableInterfaceEndpoints 를 켜면 인터페이스 엔드포인트가 생긴다', () => {
    const t = synth({ enableInterfaceEndpoints: 'true' }).network;
    const endpoints = t.findResources('AWS::EC2::VPCEndpoint', {
      Properties: { VpcEndpointType: 'Interface' },
    });
    expect(Object.keys(endpoints).length).toBeGreaterThan(0);
  });

  test('Fargate 태스크는 ALB 에서 오는 8080 만 받는다', () => {
    synth().network.hasResourceProperties('AWS::EC2::SecurityGroupIngress', {
      FromPort: 8080,
      ToPort: 8080,
      IpProtocol: 'tcp',
    });
  });
});

describe('데이터 계층', () => {
  test('RDS 는 최소 스펙 단일 AZ 로 만든다', () => {
    synth().data.hasResourceProperties('AWS::RDS::DBInstance', {
      DBInstanceClass: 'db.t4g.micro',
      Engine: 'mysql',
      MultiAZ: false,
      PubliclyAccessible: false,
    });
  });

  test('DB 비밀번호는 Secrets Manager 가 생성한다', () => {
    const t = synth().data;
    t.resourceCountIs('AWS::SecretsManager::Secret', 2); // RDS 자격증명 + 앱 시크릿
    t.hasResourceProperties('AWS::SecretsManager::Secret', {
      Name: 'aetheria/app',
      GenerateSecretString: Match.objectLike({ GenerateStringKey: 'JWT_SECRET' }),
    });
  });

  test('Redis 는 클러스터 모드 없이 단일 노드로 만든다', () => {
    synth().data.hasResourceProperties('AWS::ElastiCache::CacheCluster', {
      Engine: 'redis',
      CacheNodeType: 'cache.t4g.micro',
      NumCacheNodes: 1,
    });
  });
});

describe('애플리케이션 계층', () => {
  test('Fargate 태스크는 0.5 vCPU / 1GB 로 시작한다', () => {
    synth().app.hasResourceProperties('AWS::ECS::TaskDefinition', {
      Cpu: '512',
      Memory: '1024',
      RequiresCompatibilities: ['FARGATE'],
    });
  });

  test('taskCpu / taskMemory context 로 스펙을 올릴 수 있다', () => {
    synth({ taskCpu: '1024', taskMemory: '2048' }).app.hasResourceProperties(
      'AWS::ECS::TaskDefinition',
      { Cpu: '1024', Memory: '2048' },
    );
  });

  test('무중단 롤링 업데이트 설정과 자동 롤백이 걸려 있다', () => {
    synth().app.hasResourceProperties('AWS::ECS::Service', {
      DeploymentConfiguration: Match.objectLike({
        MinimumHealthyPercent: 100,
        MaximumPercent: 200,
        DeploymentCircuitBreaker: { Enable: true, Rollback: true },
      }),
      HealthCheckGracePeriodSeconds: 180,
    });
  });

  // 플레이스홀더와 실제 이미지의 헬스체크 경로가 갈리면, 부트스트랩 이후 GitHub Actions 로
  // 실제 이미지를 배포해도 타깃그룹은 옛 경로를 계속 본다(경로는 CDK 소유라 워크플로가 못 고친다).
  // 그래서 경로는 imageTag 유무와 무관하게 하나여야 한다.
  test('헬스체크 경로는 imageTag 유무와 무관하게 ALB 전용 그룹으로 고정된다', () => {
    synth({ imageTag: 'abc1234' }).app.hasResourceProperties(
      'AWS::ElasticLoadBalancingV2::TargetGroup',
      { HealthCheckPath: '/actuator/health/alb', Port: 8080 },
    );
    synth().app.hasResourceProperties('AWS::ElasticLoadBalancingV2::TargetGroup', {
      HealthCheckPath: '/actuator/health/alb',
    });
  });

  test('ECR 라이프사이클 정책으로 오래된 이미지를 지운다', () => {
    synth().app.hasResourceProperties('AWS::ECR::Repository', {
      RepositoryName: 'aetheria-backend',
      LifecyclePolicy: Match.objectLike({ LifecyclePolicyText: Match.anyValue() }),
    });
  });

  test('SQS 는 DLQ 를 붙여 3회 실패 시 격리한다', () => {
    synth().app.hasResourceProperties('AWS::SQS::Queue', {
      QueueName: 'aetheria-ai-notifications',
      RedrivePolicy: Match.objectLike({ maxReceiveCount: 3 }),
    });
  });

  test('인증서가 없으면 80 이 곧 forward 리스너다', () => {
    const t = synth().app;
    t.resourceCountIs('AWS::ElasticLoadBalancingV2::Listener', 1);
    t.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
      Port: 80,
      Protocol: 'HTTP',
      DefaultActions: [Match.objectLike({ Type: 'forward' })],
    });
  });

  test('인증서를 주면 443 forward + 80 리다이렉트로 바뀐다', () => {
    const t = synth({
      certificateArn: 'arn:aws:acm:ap-northeast-2:123456789012:certificate/abc',
      domainName: 'api.aetheria.example',
    }).app;
    t.resourceCountIs('AWS::ElasticLoadBalancingV2::Listener', 2);
    t.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
      Port: 443,
      Protocol: 'HTTPS',
    });
    t.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
      Port: 80,
      DefaultActions: [Match.objectLike({ Type: 'redirect' })],
    });
  });

  test('SageMaker 권한은 엔드포인트 이름이 있을 때만, 그 ARN 으로만 부여된다', () => {
    const withoutEndpoint = synth().app.findResources('AWS::IAM::Policy', {
      Properties: {
        PolicyDocument: {
          Statement: Match.arrayWith([
            Match.objectLike({ Action: Match.arrayWith(['sagemaker:InvokeEndpointAsync']) }),
          ]),
        },
      },
    });
    expect(Object.keys(withoutEndpoint)).toHaveLength(0);

    synth({ sagemakerEndpointName: 'aetheria-inference' }).app.hasResourceProperties(
      'AWS::IAM::Policy',
      {
        PolicyDocument: Match.objectLike({
          Statement: Match.arrayWith([
            Match.objectLike({
              // 백엔드는 invokeEndpointAsync 를 호출한다. 동기 액션만 있으면 AccessDenied 로 막힌다.
              Action: Match.arrayWith(['sagemaker:InvokeEndpointAsync']),
              Resource: 'arn:aws:sagemaker:ap-northeast-2:123456789012:endpoint/aetheria-inference',
            }),
          ]),
        }),
      },
    );
  });
});

describe('CI/CD', () => {
  const ctx = { githubOwner: 'my-org', githubRepo: 'aetheria-backend' };

  test('GitHub 저장소 context 가 없으면 스택을 만들지 않는다', () => {
    expect(synth().cicd).toBeUndefined();
  });

  test('신뢰 정책이 특정 저장소의 특정 브랜치로 못박혀 있다', () => {
    synth(ctx).cicd!.hasResourceProperties('AWS::IAM::Role', {
      AssumeRolePolicyDocument: Match.objectLike({
        Statement: Match.arrayWith([
          Match.objectLike({
            Action: 'sts:AssumeRoleWithWebIdentity',
            Condition: {
              StringEquals: {
                'token.actions.githubusercontent.com:sub':
                  'repo:my-org/aetheria-backend:ref:refs/heads/master',
                'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
              },
            },
          }),
        ]),
      }),
    });
  });

  test('PassRole 은 ECS 태스크 서비스로만 제한된다', () => {
    synth(ctx).cicd!.hasResourceProperties('AWS::IAM::Policy', {
      PolicyDocument: Match.objectLike({
        Statement: Match.arrayWith([
          Match.objectLike({
            Action: 'iam:PassRole',
            Condition: { StringEquals: { 'iam:PassedToService': 'ecs-tasks.amazonaws.com' } },
          }),
        ]),
      }),
    });
  });
});

/**
 * 백엔드와의 계약 검증.
 *
 * 이 저장소가 합성하는 값 중 백엔드 application.yml 의 플레이스홀더 이름과 어긋나면
 * 컨테이너는 "Could not resolve placeholder" 로 기동조차 하지 못한다.
 * 그런데 그 실패는 synth 도 cdk deploy 도 잡아 주지 않고, ECS 태스크가 죽고 나서야 드러난다.
 * 이름 하나가 조용히 어긋나는 것을 여기서 막는다.
 */
describe('백엔드 계약', () => {
  /** 컨테이너 정의에서 Name/Value 목록을 이름 집합으로 바꾼다. */
  function containerKeys(template: Template, field: 'Environment' | 'Secrets'): string[] {
    const taskDefs = template.findResources('AWS::ECS::TaskDefinition');
    const definition = Object.values(taskDefs)[0].Properties.ContainerDefinitions[0];
    return (definition[field] ?? []).map((entry: { Name: string }) => entry.Name);
  }

  test('백엔드가 기본값 없이 요구하는 환경변수를 빠짐없이 주입한다', () => {
    const names = containerKeys(synth().app, 'Environment');

    // application.yml 에 ${...} 로만 적혀 있어 기본값이 없는 것들.
    expect(names).toEqual(
      expect.arrayContaining([
        'SPRING_PROFILES_ACTIVE',
        'DATABASE_URL',
        // RedisConfig 가 커스텀 redis.* 프리픽스로 커넥션을 만든다. SPRING_DATA_REDIS_* 로는 안 된다.
        'REDIS_HOST',
        'REDIS_PORT',
        'REDIS_CONNECT_TIMEOUT',
        'REDIS_MAX_POOL',
        'JWT_REFRESH_TOKEN_COOKIE',
        'KAKAO_REDIRECT_URI',
        'GOOGLE_REDIRECT_URI',
        'ENCRYPTION_ALGORITHM',
        'FRONTEND_DOMAIN',
        'DEVELOP_SERVER_DOMAIN',
        'PROD_SERVER_DOMAIN',
        'RATE_LIMIT_USER_CAPACITY',
        'RATE_LIMIT_USER_REFILL_RATE',
        'RATE_LIMIT_IP_CAPACITY',
        'RATE_LIMIT_IP_REFILL_RATE',
        'AWS_S3_AI_INPUT_BUCKET',
        'AWS_SQS_AI_NOTIFICATION_QUEUE_NAME',
        'AWS_SAGEMAKER_ENDPOINT_NAME',
      ]),
    );

    // 백엔드가 읽지 않는 이름은 주지 않는다. 남겨 두면 "주입했으니 됐다" 는 착각을 만든다.
    expect(names).not.toContain('SPRING_DATASOURCE_URL');
    expect(names).not.toContain('SPRING_DATA_REDIS_HOST');
    expect(names).not.toContain('SPRING_R2DBC_URL'); // R2DBC 의존성 자체가 없다
    expect(names).not.toContain('AETHERIA_S3_BUCKET');
  });

  test('시크릿은 백엔드가 읽는 이름으로만 주입된다', () => {
    const names = containerKeys(synth().app, 'Secrets');

    // 정확히 이 집합이어야 한다. 백엔드가 읽지 않는 KAKAO_CLIENT_SECRET / KAKAO_REST_API_KEY 가
    // 다시 들어오면 여기서 걸린다.
    expect(names.sort()).toEqual([
      'DATABASE_PASSWORD',
      'DATABASE_USERNAME',
      'DISCORD_WEB_HOOK',
      'ENCRYPTION_SECRET_KEY_V1',
      'ENCRYPTION_SECRET_KEY_V2',
      'GOOGLE_CLIENT_ID',
      'GOOGLE_CLIENT_SECRET',
      'JWT_SECRET',
      'KAKAO_ADMIN_KEY',
      'KAKAO_CLIENT_ID',
    ]);
  });

  test('aetheria/app 시크릿 템플릿이 주입하는 키를 실제로 담고 있다', () => {
    const secrets = synth().data.findResources('AWS::SecretsManager::Secret');
    const appSecret = Object.values(secrets).find(
      (r: any) => r.Properties.Name === 'aetheria/app',
    ) as any;

    const template = JSON.parse(appSecret.Properties.GenerateSecretString.SecretStringTemplate);
    expect(Object.keys(template).sort()).toEqual([
      'DISCORD_WEB_HOOK',
      'ENCRYPTION_SECRET_KEY_V1',
      'ENCRYPTION_SECRET_KEY_V2',
      'GOOGLE_CLIENT_ID',
      'GOOGLE_CLIENT_SECRET',
      'KAKAO_ADMIN_KEY',
      'KAKAO_CLIENT_ID',
    ]);
    expect(appSecret.Properties.GenerateSecretString.GenerateStringKey).toBe('JWT_SECRET');
  });

  test('컨테이너 이름은 배포 워크플로의 jq 치환 대상과 같아야 한다', () => {
    synth().app.hasResourceProperties('AWS::ECS::TaskDefinition', {
      ContainerDefinitions: Match.arrayWith([
        Match.objectLike({ Name: 'aetheria-backend' }),
      ]),
    });
  });
});

describe('SageMaker 콜백 경로', () => {
  test('성공/실패 토픽이 알림 큐를 raw 전송으로 구독한다', () => {
    const t = synth().app;

    t.resourceCountIs('AWS::SNS::Topic', 2);
    t.hasResourceProperties('AWS::SNS::Topic', { TopicName: 'aetheria-ai-success' });
    t.hasResourceProperties('AWS::SNS::Topic', { TopicName: 'aetheria-ai-error' });

    const subscriptions = t.findResources('AWS::SNS::Subscription');
    expect(Object.keys(subscriptions)).toHaveLength(2);
    // raw 전송이 아니면 SNS 봉투가 씌워져 SageMakerNotificationDto 역직렬화가 전부 실패한다.
    for (const sub of Object.values(subscriptions) as any[]) {
      expect(sub.Properties.Protocol).toBe('sqs');
      expect(sub.Properties.RawMessageDelivery).toBe(true);
    }

    // SNS 가 큐에 넣을 수 있어야 한다.
    t.resourceCountIs('AWS::SQS::QueuePolicy', 2);
  });

  test('백엔드는 큐를 소비만 한다. 발행 권한은 주지 않는다', () => {
    const policies = synth().app.findResources('AWS::IAM::Policy');
    const statements = Object.values(policies).flatMap(
      (p: any) => p.Properties.PolicyDocument.Statement,
    );
    const actions = statements.flatMap((s: any) =>
      Array.isArray(s.Action) ? s.Action : [s.Action],
    );
    expect(actions).toContain('sqs:ReceiveMessage');
    expect(actions).not.toContain('sqs:SendMessage');
  });
});
