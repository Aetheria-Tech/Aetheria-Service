import * as cdk from 'aws-cdk-lib/core';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import { Construct } from 'constructs';
import { AetheriaConfig } from './config';

export interface CicdStackProps extends cdk.StackProps {
  readonly config: AetheriaConfig;
  readonly repository: ecr.IRepository;
  readonly cluster: ecs.ICluster;
  readonly service: ecs.IBaseService;
  readonly taskRole: iam.IRole;
  readonly executionRole: iam.IRole;
}

const GITHUB_OIDC_URL = 'https://token.actions.githubusercontent.com';
const GITHUB_OIDC_DOMAIN = 'token.actions.githubusercontent.com';

/**
 * GitHub Actions 가 액세스 키 없이(OIDC) 배포할 수 있게 하는 신뢰 관계와 최소 권한 역할.
 *
 * githubOwner/githubRepo context 가 없으면 bin/ 에서 이 스택 자체를 만들지 않는다.
 */
export class AetheriaCicdStack extends cdk.Stack {
  public readonly deployRole: iam.Role;

  constructor(scope: Construct, id: string, props: CicdStackProps) {
    super(scope, id, props);

    const { config } = props;

    if (!config.githubOwner || !config.githubRepo) {
      throw new Error('AetheriaCicdStack 에는 githubOwner / githubRepo context 가 필요합니다.');
    }

    // OIDC 공급자는 계정당 하나만 존재할 수 있다. 다른 프로젝트가 이미 만들어 두었다면
    // `-c createGithubOidcProvider=false` 로 기존 것을 임포트한다.
    const provider: iam.IOpenIdConnectProvider = config.createGithubOidcProvider
      ? new iam.OpenIdConnectProvider(this, 'GithubOidcProvider', {
          url: GITHUB_OIDC_URL,
          clientIds: ['sts.amazonaws.com'],
        })
      : iam.OpenIdConnectProvider.fromOpenIdConnectProviderArn(
          this,
          'GithubOidcProvider',
          cdk.Arn.format(
            {
              service: 'iam',
              region: '',
              resource: 'oidc-provider',
              resourceName: GITHUB_OIDC_DOMAIN,
            },
            this,
          ),
        );

    const subject = `repo:${config.githubOwner}/${config.githubRepo}:ref:refs/heads/${config.githubBranch}`;

    this.deployRole = new iam.Role(this, 'GithubActionsDeployRole', {
      roleName: 'aetheria-github-actions-deploy',
      description: 'GitHub Actions 배포 역할 (ECR push + ECS 서비스 갱신)',
      maxSessionDuration: cdk.Duration.hours(1),
      // sub 를 특정 브랜치까지 못박는다. `repo:owner/*` 같은 와일드카드를 쓰면
      // 그 저장소의 어떤 브랜치·포크에서도 이 역할을 가져갈 수 있다.
      assumedBy: new iam.WebIdentityPrincipal(provider.openIdConnectProviderArn, {
        StringEquals: {
          [`${GITHUB_OIDC_DOMAIN}:sub`]: subject,
          [`${GITHUB_OIDC_DOMAIN}:aud`]: 'sts.amazonaws.com',
        },
      }),
    });

    // ECR 로그인 토큰 발급은 리소스를 지정할 수 없는 API 라 * 를 쓸 수밖에 없다.
    this.deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: 'EcrAuth',
        actions: ['ecr:GetAuthorizationToken'],
        resources: ['*'],
      }),
    );

    this.deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: 'EcrPush',
        actions: [
          'ecr:BatchCheckLayerAvailability',
          'ecr:BatchGetImage',
          'ecr:CompleteLayerUpload',
          'ecr:DescribeImages',
          'ecr:GetDownloadUrlForLayer',
          'ecr:InitiateLayerUpload',
          'ecr:PutImage',
          'ecr:UploadLayerPart',
        ],
        resources: [props.repository.repositoryArn],
      }),
    );

    // RegisterTaskDefinition / DescribeTaskDefinition 도 리소스 스코프를 지원하지 않는다.
    this.deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: 'EcsTaskDefinition',
        actions: ['ecs:RegisterTaskDefinition', 'ecs:DescribeTaskDefinition'],
        resources: ['*'],
      }),
    );

    this.deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: 'EcsServiceUpdate',
        actions: ['ecs:UpdateService', 'ecs:DescribeServices'],
        resources: [props.service.serviceArn],
        conditions: {
          ArnEquals: { 'ecs:cluster': props.cluster.clusterArn },
        },
      }),
    );

    // 새 태스크 정의를 등록하려면 그 안에 적힌 두 역할을 넘길 수 있어야 한다.
    // 조건 없이 열어 두면 계정의 아무 역할이나 ECS 로 넘길 수 있으므로 서비스까지 못박는다.
    this.deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: 'PassTaskRoles',
        actions: ['iam:PassRole'],
        resources: [props.taskRole.roleArn, props.executionRole.roleArn],
        conditions: {
          StringEquals: { 'iam:PassedToService': 'ecs-tasks.amazonaws.com' },
        },
      }),
    );

    new cdk.CfnOutput(this, 'DeployRoleArn', {
      value: this.deployRole.roleArn,
      description: 'GitHub Secrets 의 AWS_DEPLOY_ROLE_ARN 에 넣을 값',
    });
    new cdk.CfnOutput(this, 'TrustedSubject', {
      value: subject,
      description: '이 역할을 가져갈 수 있는 GitHub Actions 주체',
    });
  }
}
