import * as cdk from 'aws-cdk-lib/core';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as elasticache from 'aws-cdk-lib/aws-elasticache';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import { Construct } from 'constructs';
import { AetheriaConfig } from './config';

export interface DataStackProps extends cdk.StackProps {
  readonly config: AetheriaConfig;
  readonly vpc: ec2.IVpc;
  readonly databaseSecurityGroup: ec2.ISecurityGroup;
  readonly redisSecurityGroup: ec2.ISecurityGroup;
}

/** RDS MySQL, ElastiCache Redis, 그리고 애플리케이션 시크릿. */
export class AetheriaDataStack extends cdk.Stack {
  public readonly database: rds.DatabaseInstance;
  /** RDS 가 자동 생성한 자격증명 시크릿 (username/password 키 포함). */
  public readonly databaseSecret: secretsmanager.ISecret;
  public readonly databaseName = 'aetheria';
  public readonly redisEndpointAddress: string;
  public readonly redisEndpointPort: string;
  /** 외부 연동 자격증명. 값은 배포 후 사람이 채운다. */
  public readonly appSecret: secretsmanager.Secret;

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    const { config, vpc } = props;

    // 한글 데이터가 들어가므로 utf8mb4 를 서버 기본값으로 못박는다.
    const parameterGroup = new rds.ParameterGroup(this, 'DbParameterGroup', {
      engine: rds.DatabaseInstanceEngine.mysql({ version: rds.MysqlEngineVersion.VER_8_0 }),
      description: 'Aetheria MySQL parameters (utf8mb4)',
      parameters: {
        character_set_server: 'utf8mb4',
        collation_server: 'utf8mb4_unicode_ci',
        time_zone: 'Asia/Seoul',
      },
    });

    this.database = new rds.DatabaseInstance(this, 'Database', {
      engine: rds.DatabaseInstanceEngine.mysql({ version: rds.MysqlEngineVersion.VER_8_0 }),
      instanceType: new ec2.InstanceType(config.dbInstanceClass),
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [props.databaseSecurityGroup],
      // 비용 최소화: 단일 AZ. 가용성이 필요해지면 multiAz: true 로 올린다(요금 2배).
      multiAz: false,
      allocatedStorage: config.dbAllocatedStorage,
      storageType: rds.StorageType.GP3,
      // 스토리지 자동 확장을 켜두지 않으면 디스크가 차는 순간 DB 가 멈춘다. 상한을 둬서 비용도 묶는다.
      maxAllocatedStorage: config.dbAllocatedStorage * 5,
      databaseName: this.databaseName,
      // 비밀번호를 CDK 코드나 git 에 두지 않기 위해 Secrets Manager 가 생성하게 한다.
      credentials: rds.Credentials.fromGeneratedSecret('aetheria', {
        secretName: 'aetheria/rds',
      }),
      parameterGroup,
      backupRetention: cdk.Duration.days(7),
      deleteAutomatedBackups: !config.retainData,
      deletionProtection: config.retainData,
      removalPolicy: config.retainData ? cdk.RemovalPolicy.SNAPSHOT : cdk.RemovalPolicy.DESTROY,
      enablePerformanceInsights: false,
      autoMinorVersionUpgrade: true,
    });

    if (!this.database.secret) {
      throw new Error('RDS 시크릿이 생성되지 않았습니다. credentials 설정을 확인하세요.');
    }
    this.databaseSecret = this.database.secret;

    // ElastiCache 에는 L2 construct 가 없어 L1(Cfn*) 을 직접 쓴다.
    const redisSubnetGroup = new elasticache.CfnSubnetGroup(this, 'RedisSubnetGroup', {
      cacheSubnetGroupName: 'aetheria-redis-subnets',
      description: 'Aetheria Redis private isolated subnets',
      subnetIds: vpc.selectSubnets({ subnetType: ec2.SubnetType.PRIVATE_ISOLATED }).subnetIds,
    });

    const redis = new elasticache.CfnCacheCluster(this, 'Redis', {
      clusterName: 'aetheria-redis',
      engine: 'redis',
      cacheNodeType: config.redisNodeType,
      // 클러스터 모드 비활성화 · 단일 노드 · Multi-AZ 없음 (CLAUDE.md §3 비용 최소 구성).
      numCacheNodes: 1,
      port: 6379,
      cacheSubnetGroupName: redisSubnetGroup.cacheSubnetGroupName!,
      vpcSecurityGroupIds: [props.redisSecurityGroup.securityGroupId],
      autoMinorVersionUpgrade: true,
    });
    redis.addDependency(redisSubnetGroup);

    this.redisEndpointAddress = redis.attrRedisEndpointAddress;
    this.redisEndpointPort = redis.attrRedisEndpointPort;

    // 백엔드가 시크릿으로 받아야 하는 값들. 키 이름은 application.yml 의 플레이스홀더와 1:1 이다.
    // JWT_SECRET 만 CDK 가 무작위 생성하고, 나머지는 빈 문자열로 자리만 만들어 둔 뒤
    // 배포 후 콘솔이나 `aws secretsmanager put-secret-value` 로 사람이 채운다.
    //
    // ⚠️ ENCRYPTION_SECRET_KEY_V1/V2 는 이메일 등 PII 컬럼을 AES-GCM 으로 복호화하는 키다.
    //    기존 DB 데이터를 이어 쓸 거라면 반드시 **기존에 쓰던 키를 그대로** 넣어야 한다.
    //    새 키를 넣으면 기존 행이 복호화되지 않는다.
    //    또한 값이 빈 문자열인 상태로 실제 이미지를 배포하면 암호화 빈 초기화에서 기동에 실패하므로,
    //    첫 코드 배포 전에 이 시크릿을 채워야 한다.
    this.appSecret = new secretsmanager.Secret(this, 'AppSecret', {
      secretName: 'aetheria/app',
      description: 'Aetheria 외부 연동 자격증명 (카카오/구글 OAuth, PII 암호화 키, JWT, Discord)',
      generateSecretString: {
        secretStringTemplate: JSON.stringify({
          // 백엔드의 kakao.client-id 가 곧 카카오 REST API 키다.
          KAKAO_CLIENT_ID: '',
          // 회원 탈퇴 시 소셜 연결 해제에 쓰는 어드민 키.
          KAKAO_ADMIN_KEY: '',
          GOOGLE_CLIENT_ID: '',
          GOOGLE_CLIENT_SECRET: '',
          ENCRYPTION_SECRET_KEY_V1: '',
          ENCRYPTION_SECRET_KEY_V2: '',
          DISCORD_WEB_HOOK: '',
        }),
        // jjwt 의 HS512 는 64byte 이상의 키를 요구한다. 구두점을 빼도 64자면 64byte 다.
        generateStringKey: 'JWT_SECRET',
        excludePunctuation: true,
        passwordLength: 64,
      },
      removalPolicy: config.retainData ? cdk.RemovalPolicy.RETAIN : cdk.RemovalPolicy.DESTROY,
    });

    new cdk.CfnOutput(this, 'DatabaseEndpoint', { value: this.database.dbInstanceEndpointAddress });
    new cdk.CfnOutput(this, 'DatabaseSecretName', { value: this.databaseSecret.secretName });
    new cdk.CfnOutput(this, 'RedisEndpoint', { value: this.redisEndpointAddress });
    new cdk.CfnOutput(this, 'AppSecretName', { value: this.appSecret.secretName });
  }
}
