import * as cdk from 'aws-cdk-lib/core';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as snsSubscriptions from 'aws-cdk-lib/aws-sns-subscriptions';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as rds from 'aws-cdk-lib/aws-rds';
import { Construct } from 'constructs';
import { AetheriaConfig } from './config';

export interface AppStackProps extends cdk.StackProps {
  readonly config: AetheriaConfig;
  readonly vpc: ec2.IVpc;
  readonly albSecurityGroup: ec2.ISecurityGroup;
  readonly appSecurityGroup: ec2.ISecurityGroup;
  readonly database: rds.IDatabaseInstance;
  readonly databaseSecret: secretsmanager.ISecret;
  readonly databaseName: string;
  readonly redisEndpointAddress: string;
  readonly redisEndpointPort: string;
  readonly appSecret: secretsmanager.ISecret;
}

const CONTAINER_PORT = 8080;

/**
 * 애플리케이션 런타임 전체: ECR, 비동기 파이프라인용 S3/SQS, ECS Fargate 서비스, ALB.
 */
export class AetheriaAppStack extends cdk.Stack {
  public readonly repository: ecr.Repository;
  public readonly cluster: ecs.Cluster;
  public readonly service: ecs.FargateService;
  public readonly taskRole: iam.Role;
  public readonly executionRole: iam.Role;
  public readonly loadBalancer: elbv2.ApplicationLoadBalancer;
  public readonly aiBucket: s3.Bucket;
  /** SageMaker 비동기 추론의 완료/실패 알림이 도착하는 큐. 백엔드 리스너가 이것을 소비한다. */
  public readonly notificationQueue: sqs.Queue;
  public readonly deadLetterQueue: sqs.Queue;
  /** SageMaker 엔드포인트의 NotificationConfig 에 등록할 토픽. */
  public readonly inferenceSuccessTopic: sns.Topic;
  public readonly inferenceErrorTopic: sns.Topic;

  constructor(scope: Construct, id: string, props: AppStackProps) {
    super(scope, id, props);

    const { config, vpc } = props;

    // ---------------------------------------------------------------- ECR
    this.repository = new ecr.Repository(this, 'Repository', {
      repositoryName: 'aetheria-backend',
      imageScanOnPush: true,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      // git sha 태그를 쓰므로 이미지가 계속 쌓인다. 오래된 것은 자동으로 지워 스토리지 비용을 묶는다.
      lifecycleRules: [
        {
          rulePriority: 1,
          description: '태그 없는 이미지는 1일 후 삭제',
          tagStatus: ecr.TagStatus.UNTAGGED,
          maxImageAge: cdk.Duration.days(1),
        },
        {
          rulePriority: 2,
          description: '최근 10개 이미지만 보관',
          tagStatus: ecr.TagStatus.ANY,
          maxImageCount: 10,
        },
      ],
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    // ------------------------------------------------- 비동기 파이프라인 (S3 + SQS)
    // 백엔드가 프롬프트 JSON 을 inputs/ 로 올리고, SageMaker 가 결과를 outputs/ 로 쓴다. 한 버킷을 공용한다.
    this.aiBucket = new s3.Bucket(this, 'RequestBucket', {
      bucketName: `aetheria-ai-requests-${this.account}-${this.region}`,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      versioned: false,
      // 요청 JSON 은 처리되고 나면 필요 없는 임시 데이터라 30일 후 만료시킨다.
      lifecycleRules: [
        {
          id: 'expire-processed-requests',
          expiration: cdk.Duration.days(30),
          abortIncompleteMultipartUploadAfter: cdk.Duration.days(1),
        },
      ],
      removalPolicy: config.retainData ? cdk.RemovalPolicy.RETAIN : cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: !config.retainData,
    });

    this.deadLetterQueue = new sqs.Queue(this, 'RequestDlq', {
      queueName: 'aetheria-ai-notifications-dlq',
      retentionPeriod: cdk.Duration.days(14),
      enforceSSL: true,
    });

    this.notificationQueue = new sqs.Queue(this, 'RequestQueue', {
      queueName: 'aetheria-ai-notifications',
      // 리스너가 아직 PENDING 인 태스크를 만나면 일부러 예외를 던져 가시성 타임아웃 뒤 재시도를 유도한다.
      // 이 값이 너무 짧으면 재시도가 몰리고, 실제 처리 시간보다 짧으면 같은 메시지가 중복 처리된다.
      visibilityTimeout: cdk.Duration.minutes(5),
      retentionPeriod: cdk.Duration.days(4),
      enforceSSL: true,
      deadLetterQueue: {
        queue: this.deadLetterQueue,
        maxReceiveCount: 3,
      },
    });

    // SageMaker 비동기 추론의 NotificationConfig 는 SNS 토픽만 받는다. SQS 를 직접 지정할 수 없다.
    // 따라서 토픽을 만들고 그 토픽이 위 큐를 구독하게 해야 콜백이 리스너까지 도달한다.
    this.inferenceSuccessTopic = new sns.Topic(this, 'InferenceSuccessTopic', {
      topicName: 'aetheria-ai-success',
      displayName: 'Aetheria SageMaker 비동기 추론 성공 알림',
    });
    this.inferenceErrorTopic = new sns.Topic(this, 'InferenceErrorTopic', {
      topicName: 'aetheria-ai-error',
      displayName: 'Aetheria SageMaker 비동기 추론 실패 알림',
    });

    for (const topic of [this.inferenceSuccessTopic, this.inferenceErrorTopic]) {
      topic.addSubscription(
        new snsSubscriptions.SqsSubscription(this.notificationQueue, {
          // raw 전송이 아니면 SNS 봉투({"Type":"Notification","Message":"..."})가 씌워진다.
          // 백엔드의 SageMakerNotificationDto 는 SageMaker 알림 JSON 을 그대로 역직렬화하므로
          // 봉투가 씌워지는 순간 모든 메시지가 파싱에 실패해 DLQ 로 빠진다.
          rawMessageDelivery: true,
        }),
      );
    }

    // ---------------------------------------------------------------- IAM
    // 두 역할을 명시적으로 만든다. CI/CD 스택이 iam:PassRole 을 이 두 ARN 으로만 스코프해야 하기 때문.
    this.executionRole = new iam.Role(this, 'TaskExecutionRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      description: 'Aetheria ECS 태스크 실행 역할 (이미지 pull, 로그, 시크릿 주입)',
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
      ],
    });

    this.taskRole = new iam.Role(this, 'TaskRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      description: 'Aetheria 애플리케이션 런타임 역할 (S3, SQS, SageMaker)',
    });

    this.aiBucket.grantReadWrite(this.taskRole);
    // 큐에 메시지를 넣는 주체는 SageMaker(→SNS)다. 백엔드는 소비만 하므로 SendMessage 는 주지 않는다.
    // grantConsumeMessages 에 sqs:GetQueueUrl 이 포함되어 있어, 이름으로 큐를 찾는 @SqsListener 가 동작한다.
    this.notificationQueue.grantConsumeMessages(this.taskRole);
    this.deadLetterQueue.grantConsumeMessages(this.taskRole);

    if (config.sagemakerEndpointName) {
      // 엔드포인트 이름이 정해진 경우에만, 그 하나의 ARN 으로만 허용한다. 와일드카드를 쓰지 않는다.
      this.taskRole.addToPolicy(
        new iam.PolicyStatement({
          // 백엔드는 SageMakerAsyncAdapter 에서 invokeEndpointAsync 를 호출한다.
          // InvokeEndpoint 만 주면 실제 호출은 AccessDenied 로 막힌다.
          actions: ['sagemaker:InvokeEndpoint', 'sagemaker:InvokeEndpointAsync'],
          resources: [
            cdk.Arn.format(
              {
                service: 'sagemaker',
                resource: 'endpoint',
                resourceName: config.sagemakerEndpointName,
              },
              this,
            ),
          ],
        }),
      );
    } else {
      cdk.Annotations.of(this).addInfo(
        'sagemakerEndpointName context 가 없어 InvokeEndpoint 권한을 생략합니다. ' +
          '엔드포인트 배포 후 `-c sagemakerEndpointName=<이름>` 으로 재배포하세요.',
      );
    }

    // ---------------------------------------------------------------- ECS
    this.cluster = new ecs.Cluster(this, 'Cluster', {
      clusterName: 'aetheria-cluster',
      vpc,
      // Container Insights 는 CloudWatch 커스텀 메트릭 요금이 붙어 기본은 끈다.
      containerInsightsV2: ecs.ContainerInsights.DISABLED,
    });

    const logGroup = new logs.LogGroup(this, 'ServiceLogGroup', {
      logGroupName: '/aetheria/backend',
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const taskDefinition = new ecs.FargateTaskDefinition(this, 'TaskDefinition', {
      family: 'aetheria-backend',
      cpu: config.taskCpu,
      memoryLimitMiB: config.taskMemory,
      taskRole: this.taskRole,
      executionRole: this.executionRole,
      runtimePlatform: {
        cpuArchitecture: ecs.CpuArchitecture.X86_64,
        operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
      },
    });

    // 첫 배포 시 ECR 은 비어 있다. 그 상태로 ECR 이미지를 참조하면 태스크가 이미지를 못 받아
    // 서비스가 끝내 stabilize 되지 못하고 스택이 롤백된다. imageTag 가 없을 때는
    // 8080 을 듣는 플레이스홀더로 인프라를 먼저 세우고, 실제 이미지는 GitHub Actions 가 밀어 넣는다.
    const isPlaceholder = !config.imageTag;
    // 모든 경로에 200 을 돌려준다. 덕분에 타깃그룹 헬스체크 경로를 실제 이미지와 동일하게
    // 고정할 수 있고, 부트스트랩 이후 헬스체크 경로가 '/' 에 고착되는 문제가 사라진다.
    const placeholderCommand = [
      'sh',
      '-c',
      `printf 'server { listen ${CONTAINER_PORT}; location / { return 200 "bootstrap"; } }' ` +
        "> /etc/nginx/conf.d/default.conf && nginx -g 'daemon off;'",
    ];

    const container = taskDefinition.addContainer('backend', {
      containerName: 'aetheria-backend',
      image: isPlaceholder
        ? ecs.ContainerImage.fromRegistry('public.ecr.aws/docker/library/nginx:alpine')
        : ecs.ContainerImage.fromEcrRepository(this.repository, config.imageTag),
      command: isPlaceholder ? placeholderCommand : undefined,
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'backend', logGroup }),
      // 아래 이름은 전부 백엔드 application.yml 의 플레이스홀더와 1:1 로 맞춘 것이다.
      // 백엔드 플레이스홀더에는 기본값이 없어, 하나라도 빠지면 컨테이너가
      // "Could not resolve placeholder" 로 기동에 실패한다.
      environment: {
        // local/dev 프로파일에는 @Primary 인 FakeS3Adapter·FakeSageMakerAdapter 가 있어
        // 실제 AWS 호출이 목으로 가려진다. prod 여야 진짜 어댑터가 쓰인다.
        SPRING_PROFILES_ACTIVE: 'prod',
        SERVER_PORT: String(CONTAINER_PORT),
        // ALB 뒤에서는 원격 주소가 전부 ALB 의 사설 IP 다. X-Forwarded-For 를 신뢰하지 않으면
        // IP 단위 레이트리밋이 모든 사용자를 한 버킷에 몰아넣는다.
        SERVER_FORWARD_HEADERS_STRATEGY: 'framework',
        AWS_REGION: this.region,

        // 백엔드는 JPA(JDBC) 만 쓴다. R2DBC 의존성 자체가 없어 r2dbc URL 은 주지 않는다.
        // useSSL 은 connector 9.x 에서 사용 중단 예정이라 sslMode 를 쓴다.
        DATABASE_URL: `jdbc:mysql://${props.database.dbInstanceEndpointAddress}:${props.database.dbInstanceEndpointPort}/${props.databaseName}?sslMode=REQUIRED&serverTimezone=Asia/Seoul&characterEncoding=UTF-8`,

        // 백엔드의 RedisConfig 는 spring.data.redis.* 가 아니라 커스텀 redis.* 프리픽스로
        // LettuceConnectionFactory 를 직접 만든다. SPRING_DATA_REDIS_HOST 로는 연결되지 않는다.
        REDIS_HOST: props.redisEndpointAddress,
        REDIS_PORT: props.redisEndpointPort,
        REDIS_CONNECT_TIMEOUT: '5000',
        REDIS_MAX_POOL: '8',

        // 입력(inputs/)과 출력(outputs/)을 한 버킷에서 쓴다.
        AWS_S3_AI_INPUT_BUCKET: this.aiBucket.bucketName,
        AWS_S3_AI_OUTPUT_BUCKET: this.aiBucket.bucketName,
        // @SqsListener 가 이름으로 GetQueueUrl 을 호출한다. URL 이 아니라 이름이어야 한다.
        AWS_SQS_AI_NOTIFICATION_QUEUE_NAME: this.notificationQueue.queueName,
        AWS_SAGEMAKER_ENDPOINT_NAME: config.sagemakerEndpointName ?? '',

        JWT_REFRESH_TOKEN_COOKIE: 'refreshToken',
        ENCRYPTION_ALGORITHM: 'AES/GCM/NoPadding',

        RATE_LIMIT_USER_CAPACITY: String(config.rateLimitUserCapacity),
        RATE_LIMIT_USER_REFILL_RATE: String(config.rateLimitUserRefillRate),
        RATE_LIMIT_IP_CAPACITY: String(config.rateLimitIpCapacity),
        RATE_LIMIT_IP_REFILL_RATE: String(config.rateLimitIpRefillRate),

        // 운영에서 모든 쿼리를 CloudWatch 로 흘려보내지 않는다.
        JPA_SHOW_SQL: 'false',

        // 컨테이너 메모리에 맞춰 JVM 힙을 잡는다. 이 값이 없으면 JVM 이 기본 비율로 힙을 잡아
        // 작은 태스크에서 OOM-kill 되기 쉽다.
        JAVA_TOOL_OPTIONS: '-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0',
      },
      secrets: {
        DATABASE_USERNAME: ecs.Secret.fromSecretsManager(props.databaseSecret, 'username'),
        DATABASE_PASSWORD: ecs.Secret.fromSecretsManager(props.databaseSecret, 'password'),
        JWT_SECRET: ecs.Secret.fromSecretsManager(props.appSecret, 'JWT_SECRET'),
        // 백엔드의 kakao.client-id 가 곧 카카오 REST API 키다. 별도의 client-secret 은 읽지 않는다.
        KAKAO_CLIENT_ID: ecs.Secret.fromSecretsManager(props.appSecret, 'KAKAO_CLIENT_ID'),
        KAKAO_ADMIN_KEY: ecs.Secret.fromSecretsManager(props.appSecret, 'KAKAO_ADMIN_KEY'),
        GOOGLE_CLIENT_ID: ecs.Secret.fromSecretsManager(props.appSecret, 'GOOGLE_CLIENT_ID'),
        GOOGLE_CLIENT_SECRET: ecs.Secret.fromSecretsManager(props.appSecret, 'GOOGLE_CLIENT_SECRET'),
        // PII 컬럼(AES-GCM)의 복호화 키. 값이 비어 있으면 암호화 빈 초기화에서 기동에 실패한다.
        ENCRYPTION_SECRET_KEY_V1: ecs.Secret.fromSecretsManager(props.appSecret, 'ENCRYPTION_SECRET_KEY_V1'),
        ENCRYPTION_SECRET_KEY_V2: ecs.Secret.fromSecretsManager(props.appSecret, 'ENCRYPTION_SECRET_KEY_V2'),
        DISCORD_WEB_HOOK: ecs.Secret.fromSecretsManager(props.appSecret, 'DISCORD_WEB_HOOK'),
      },
    });

    container.addPortMappings({
      containerPort: CONTAINER_PORT,
      protocol: ecs.Protocol.TCP,
    });

    this.service = new ecs.FargateService(this, 'Service', {
      serviceName: 'aetheria-backend',
      cluster: this.cluster,
      taskDefinition,
      desiredCount: config.desiredCount,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [props.appSecurityGroup],
      assignPublicIp: false,
      // 무중단 롤링 업데이트: 새 태스크가 healthy 가 될 때까지 기존 태스크가 트래픽을 계속 받는다.
      minHealthyPercent: 100,
      maxHealthyPercent: 200,
      // 나쁜 이미지가 올라가면 배포를 중단하고 직전 태스크 정의로 자동 롤백한다.
      circuitBreaker: { rollback: true },
      // Spring Boot 기동 + Flyway 마이그레이션까지 끝나야 readiness 가 올라온다.
      // 이 유예가 없으면 ALB 가 기동 중인 태스크를 unhealthy 로 판정해 죽이고, 배포가 무한 반복에 빠진다.
      healthCheckGracePeriod: cdk.Duration.seconds(180),
      enableExecuteCommand: true,
    });

    // ---------------------------------------------------------------- ALB
    this.loadBalancer = new elbv2.ApplicationLoadBalancer(this, 'LoadBalancer', {
      loadBalancerName: 'aetheria-alb',
      vpc,
      internetFacing: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      securityGroup: props.albSecurityGroup,
      // 백엔드의 AI 완료 알림은 SSE 로 나가고 sse.timeout 이 10분이다.
      // 기본 60초 유휴 타임아웃을 두면 추론이 끝나기 전에 ALB 가 연결을 끊어
      // 클라이언트가 완료 이벤트를 영영 받지 못한다.
      idleTimeout: cdk.Duration.seconds(620),
    });

    const targetGroup = new elbv2.ApplicationTargetGroup(this, 'ServiceTargetGroup', {
      targetGroupName: 'aetheria-tg',
      vpc,
      port: CONTAINER_PORT,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targetType: elbv2.TargetType.IP,
      targets: [this.service],
      // 기본 300초는 배포마다 5분씩 늘어지게 만든다. WebFlux 요청은 짧으므로 30초면 충분하다.
      deregistrationDelay: cdk.Duration.seconds(30),
      healthCheck: {
        // 기본 /actuator/health 는 서킷브레이커·db·redis 를 함께 집계해서, 카카오 API 가 느려지는
        // 것만으로 503 이 되어 태스크가 강제 종료된다. ALB 전용 그룹은 readinessState 만 본다.
        // 플레이스홀더 nginx 도 모든 경로에 200 을 주므로 경로를 분기할 필요가 없다.
        path: '/actuator/health/alb',
        healthyHttpCodes: '200',
        interval: cdk.Duration.seconds(15),
        timeout: cdk.Duration.seconds(5),
        healthyThresholdCount: 2,
        unhealthyThresholdCount: 3,
      },
    });

    if (config.certificateArn) {
      this.loadBalancer.addListener('HttpsListener', {
        port: 443,
        protocol: elbv2.ApplicationProtocol.HTTPS,
        certificates: [elbv2.ListenerCertificate.fromArn(config.certificateArn)],
        sslPolicy: elbv2.SslPolicy.RECOMMENDED_TLS,
        defaultTargetGroups: [targetGroup],
      });
      this.loadBalancer.addListener('HttpListener', {
        port: 80,
        protocol: elbv2.ApplicationProtocol.HTTP,
        defaultAction: elbv2.ListenerAction.redirect({
          protocol: 'HTTPS',
          port: '443',
          permanent: true,
        }),
      });
    } else {
      // 도메인/인증서가 아직 없는 단계. certificateArn context 를 주면 위 분기로 바뀐다.
      this.loadBalancer.addListener('HttpListener', {
        port: 80,
        protocol: elbv2.ApplicationProtocol.HTTP,
        defaultTargetGroups: [targetGroup],
      });
    }

    // 리디렉트 URI 와 CORS 오리진은 ALB DNS 를 알아야 정해진다. 태스크 정의보다 ALB 가 뒤에
    // 만들어지므로 여기서 뒤늦게 붙인다. 도메인이 정해지면 그쪽이 우선한다.
    const publicOrigin = config.domainName
      ? `https://${config.domainName}`
      : `http://${this.loadBalancer.loadBalancerDnsName}`;

    container.addEnvironment('KAKAO_REDIRECT_URI', `${publicOrigin}/api/v1/auth/callback/kakao`);
    container.addEnvironment('GOOGLE_REDIRECT_URI', `${publicOrigin}/api/v1/auth/callback/google`);
    container.addEnvironment('PROD_SERVER_DOMAIN', publicOrigin);
    container.addEnvironment('DEVELOP_SERVER_DOMAIN', config.developOrigin ?? publicOrigin);
    container.addEnvironment('FRONTEND_DOMAIN', config.frontendOrigin ?? publicOrigin);

    // ------------------------------------------------------------ 오토스케일링
    // 유휴 시에는 desiredCount 만 돌아가므로 추가 비용이 없고, 부하가 몰릴 때만 늘어난다.
    const scaling = this.service.autoScaleTaskCount({
      minCapacity: config.desiredCount,
      maxCapacity: Math.max(config.desiredCount, config.maxCapacity),
    });
    scaling.scaleOnCpuUtilization('CpuScaling', {
      targetUtilizationPercent: 70,
      scaleInCooldown: cdk.Duration.minutes(3),
      scaleOutCooldown: cdk.Duration.minutes(1),
    });

    // ---------------------------------------------------------------- Outputs
    new cdk.CfnOutput(this, 'LoadBalancerDns', {
      value: this.loadBalancer.loadBalancerDnsName,
      description: '카카오/구글 OAuth 리디렉트 URI 등록에 사용할 ALB 주소',
    });
    new cdk.CfnOutput(this, 'EcrRepositoryUri', { value: this.repository.repositoryUri });
    new cdk.CfnOutput(this, 'ClusterName', { value: this.cluster.clusterName });
    new cdk.CfnOutput(this, 'ServiceName', { value: this.service.serviceName });
    new cdk.CfnOutput(this, 'TaskDefinitionFamily', { value: taskDefinition.family });
    new cdk.CfnOutput(this, 'RequestBucketName', { value: this.aiBucket.bucketName });
    new cdk.CfnOutput(this, 'RequestQueueUrl', { value: this.notificationQueue.queueUrl });
    new cdk.CfnOutput(this, 'InferenceSuccessTopicArn', {
      value: this.inferenceSuccessTopic.topicArn,
      description: 'SageMaker 엔드포인트 AsyncInferenceConfig.OutputConfig.NotificationConfig.SuccessTopic',
    });
    new cdk.CfnOutput(this, 'InferenceErrorTopicArn', {
      value: this.inferenceErrorTopic.topicArn,
      description: 'SageMaker 엔드포인트 AsyncInferenceConfig.OutputConfig.NotificationConfig.ErrorTopic',
    });
    new cdk.CfnOutput(this, 'InferenceOutputS3Path', {
      value: `s3://${this.aiBucket.bucketName}/outputs/`,
      description: 'SageMaker 엔드포인트 AsyncInferenceConfig.OutputConfig.S3OutputPath',
    });
  }
}
