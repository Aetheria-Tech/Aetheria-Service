import * as cdk from 'aws-cdk-lib/core';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import { Construct } from 'constructs';
import { AetheriaConfig } from './config';

export interface NetworkStackProps extends cdk.StackProps {
  readonly config: AetheriaConfig;
}

/**
 * VPC, NAT, 그리고 이 서비스의 모든 보안그룹.
 *
 * 보안그룹을 여기서 전부 소유하고 ingress 규칙 방향까지 이 스택 안에서 선언하는 이유:
 * ALB SG 를 App 스택에서 만들고 DB SG(Data 스택)에 규칙을 추가하면 스택 간 순환 참조가 생겨
 * synth 가 깨진다. 규칙을 한 스택에 모으면 다른 스택들은 SG 를 참조만 하면 된다.
 */
export class AetheriaNetworkStack extends cdk.Stack {
  public readonly vpc: ec2.Vpc;
  /** 인터넷 → ALB */
  public readonly albSecurityGroup: ec2.SecurityGroup;
  /** ALB → Fargate 태스크(8080) */
  public readonly appSecurityGroup: ec2.SecurityGroup;
  /** Fargate → RDS(3306) */
  public readonly databaseSecurityGroup: ec2.SecurityGroup;
  /** Fargate → Redis(6379) */
  public readonly redisSecurityGroup: ec2.SecurityGroup;

  constructor(scope: Construct, id: string, props: NetworkStackProps) {
    super(scope, id, props);

    const { config } = props;

    this.vpc = new ec2.Vpc(this, 'Vpc', {
      vpcName: 'aetheria-vpc',
      ipAddresses: ec2.IpAddresses.cidr('10.0.0.0/16'),
      // RDS 서브넷 그룹이 최소 2개 AZ 를 요구하므로 2 미만으로 줄일 수 없다.
      maxAzs: 2,
      // 카카오 로그인/구글 OAuth/카카오 지오코딩 등 외부 API 호출 때문에 NAT 는 필수(CLAUDE.md §3).
      // 비용 때문에 AZ 당 1개가 아니라 전체 1개만 둔다. NAT 가 있는 AZ 가 죽으면 아웃바운드가 끊기는
      // 트레이드오프를 감수하는 것이며, 가용성이 필요해지면 natGateways: 2 로 올린다.
      natGateways: 1,
      subnetConfiguration: [
        { name: 'public', subnetType: ec2.SubnetType.PUBLIC, cidrMask: 24 },
        { name: 'app', subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS, cidrMask: 24 },
        { name: 'data', subnetType: ec2.SubnetType.PRIVATE_ISOLATED, cidrMask: 24 },
      ],
    });

    // S3 게이트웨이 엔드포인트는 비용이 0원이고, ECR 이미지 레이어가 실제로는 S3 에서 내려오므로
    // NAT 데이터 처리 비용의 가장 큰 몫을 여기서 없앤다. 항상 만든다.
    this.vpc.addGatewayEndpoint('S3Endpoint', {
      service: ec2.GatewayVpcEndpointAwsService.S3,
    });

    if (config.enableInterfaceEndpoints) {
      // 인터페이스 엔드포인트는 개당 월 ~$7 고정비. 트래픽이 커져 NAT 데이터 요금이 이를 넘길 때만 켠다.
      const interfaceEndpoints: Record<string, ec2.InterfaceVpcEndpointAwsService> = {
        EcrApiEndpoint: ec2.InterfaceVpcEndpointAwsService.ECR,
        EcrDockerEndpoint: ec2.InterfaceVpcEndpointAwsService.ECR_DOCKER,
        LogsEndpoint: ec2.InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS,
        SecretsManagerEndpoint: ec2.InterfaceVpcEndpointAwsService.SECRETS_MANAGER,
        SqsEndpoint: ec2.InterfaceVpcEndpointAwsService.SQS,
        SageMakerRuntimeEndpoint: ec2.InterfaceVpcEndpointAwsService.SAGEMAKER_RUNTIME,
      };
      for (const [endpointId, service] of Object.entries(interfaceEndpoints)) {
        this.vpc.addInterfaceEndpoint(endpointId, {
          service,
          subnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
        });
      }
    }

    this.albSecurityGroup = new ec2.SecurityGroup(this, 'AlbSecurityGroup', {
      vpc: this.vpc,
      securityGroupName: 'aetheria-alb-sg',
      description: 'Aetheria ALB: 인터넷에서 오는 HTTP(S) 트래픽만 수신',
      allowAllOutbound: true,
    });

    this.appSecurityGroup = new ec2.SecurityGroup(this, 'AppSecurityGroup', {
      vpc: this.vpc,
      securityGroupName: 'aetheria-app-sg',
      description: 'Aetheria Fargate 태스크: ALB 에서 오는 8080 만 수신, 아웃바운드는 NAT 경유로 전면 허용',
      allowAllOutbound: true,
    });

    this.databaseSecurityGroup = new ec2.SecurityGroup(this, 'DatabaseSecurityGroup', {
      vpc: this.vpc,
      securityGroupName: 'aetheria-rds-sg',
      description: 'Aetheria RDS MySQL: Fargate 태스크에서만 접근 허용',
      allowAllOutbound: false,
    });

    this.redisSecurityGroup = new ec2.SecurityGroup(this, 'RedisSecurityGroup', {
      vpc: this.vpc,
      securityGroupName: 'aetheria-redis-sg',
      description: 'Aetheria ElastiCache Redis: Fargate 태스크에서만 접근 허용',
      allowAllOutbound: false,
    });

    this.albSecurityGroup.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(80), 'HTTP from internet');
    if (props.config.certificateArn) {
      this.albSecurityGroup.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(443), 'HTTPS from internet');
    }

    this.appSecurityGroup.addIngressRule(
      this.albSecurityGroup,
      ec2.Port.tcp(8080),
      'ALB → Spring Boot 컨테이너 포트',
    );

    this.databaseSecurityGroup.addIngressRule(
      this.appSecurityGroup,
      ec2.Port.tcp(3306),
      'Fargate 태스크 → MySQL',
    );

    this.redisSecurityGroup.addIngressRule(
      this.appSecurityGroup,
      ec2.Port.tcp(6379),
      'Fargate 태스크 → Redis',
    );

    new cdk.CfnOutput(this, 'VpcId', { value: this.vpc.vpcId });
  }
}
