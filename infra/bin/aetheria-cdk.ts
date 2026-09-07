#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib/core';
import { loadConfig } from '../lib/config';
import { AetheriaNetworkStack } from '../lib/network-stack';
import { AetheriaDataStack } from '../lib/data-stack';
import { AetheriaAppStack } from '../lib/app-stack';
import { AetheriaCicdStack } from '../lib/cicd-stack';

const app = new cdk.App();
const config = loadConfig(app);

// 카카오 연동 서비스이므로 서울 리전을 기본값으로 둔다.
// AZ 조회·인증서 검증 등이 계정/리전에 의존하므로 environment-agnostic 스택은 쓰지 않는다.
const env: cdk.Environment = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: process.env.CDK_DEFAULT_REGION ?? 'ap-northeast-2',
};

const network = new AetheriaNetworkStack(app, 'AetheriaNetworkStack', {
  env,
  config,
  description: 'Aetheria VPC, NAT, 보안그룹, VPC 엔드포인트',
});

const data = new AetheriaDataStack(app, 'AetheriaDataStack', {
  env,
  config,
  description: 'Aetheria RDS MySQL, ElastiCache Redis, 애플리케이션 시크릿',
  vpc: network.vpc,
  databaseSecurityGroup: network.databaseSecurityGroup,
  redisSecurityGroup: network.redisSecurityGroup,
});

const application = new AetheriaAppStack(app, 'AetheriaAppStack', {
  env,
  config,
  description: 'Aetheria ECR, S3/SQS 비동기 파이프라인, ECS Fargate, ALB',
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

// GitHub 저장소가 정해지기 전에는 이 스택을 아예 만들지 않는다.
// 신뢰 정책의 sub 조건을 채울 수 없어 만들어 봐야 쓸 수 없는 역할이 되기 때문.
if (config.githubOwner && config.githubRepo) {
  new AetheriaCicdStack(app, 'AetheriaCicdStack', {
    env,
    config,
    description: 'Aetheria GitHub Actions OIDC 배포 역할',
    repository: application.repository,
    cluster: application.cluster,
    service: application.service,
    taskRole: application.taskRole,
    executionRole: application.executionRole,
  });
} else {
  cdk.Annotations.of(app).addInfo(
    'githubOwner / githubRepo context 가 없어 AetheriaCicdStack 을 건너뜁니다. ' +
      '예: cdk deploy --all -c githubOwner=my-org -c githubRepo=aetheria-backend',
  );
}

cdk.Tags.of(app).add('Project', 'Aetheria');
cdk.Tags.of(app).add('ManagedBy', 'CDK');
