package com.serverbe.infrastructure.config;

import com.serverbe.infrastructure.config.properties.AwsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3LifecyclePolicyInitializerTest {

    @Mock
    private S3Client s3Client;

    private static final String BUCKET = "my-project-ai-inference-input-bucket";

    @Test
    @DisplayName("lifecycle.enabled 가 false면 S3 API를 전혀 호출하지 않는다")
    void doesNothingWhenDisabled() {
        // given
        AwsProperties awsProperties = awsProperties(new AwsProperties.S3.Lifecycle(false, List.of("inputs/", "temp/"), List.of(), 1));
        S3LifecyclePolicyInitializer initializer = new S3LifecyclePolicyInitializer(s3Client, awsProperties);

        // when
        initializer.run(null);

        // then
        verify(s3Client, never()).getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class));
        verify(s3Client, never()).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
    }

    @Test
    @DisplayName("버킷에 기존 Lifecycle 설정이 없을 때(최초 상태), 임시 경로에 대한 만료 규칙을 새로 생성한다")
    void createsRulesWhenNoExistingConfiguration() {
        // given
        AwsProperties awsProperties = awsProperties(new AwsProperties.S3.Lifecycle(true, List.of("inputs/", "temp/"), List.of(), 1));
        S3LifecyclePolicyInitializer initializer = new S3LifecyclePolicyInitializer(s3Client, awsProperties);

        when(s3Client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        // when
        initializer.run(null);

        // then
        ArgumentCaptor<PutBucketLifecycleConfigurationRequest> captor =
                ArgumentCaptor.forClass(PutBucketLifecycleConfigurationRequest.class);
        verify(s3Client).putBucketLifecycleConfiguration(captor.capture());

        BucketLifecycleConfiguration configuration = captor.getValue().lifecycleConfiguration();
        assertThat(configuration.rules()).hasSize(2);
        assertThat(configuration.rules())
                .extracting(LifecycleRule::status)
                .containsOnly(ExpirationStatus.ENABLED);
        assertThat(configuration.rules())
                .extracting(rule -> rule.filter().prefix())
                .containsExactlyInAnyOrder("inputs/", "temp/");
        assertThat(configuration.rules())
                .extracting(rule -> rule.expiration().days())
                .containsOnly(1);
    }

    @Test
    @DisplayName("버킷에 우리가 관리하지 않는 기존 규칙이 있으면 보존하고, 우리가 관리하는 규칙만 갱신한다")
    void preservesUnmanagedRulesAndUpdatesManagedOnes() {
        // given
        AwsProperties awsProperties = awsProperties(new AwsProperties.S3.Lifecycle(true, List.of("inputs/"), List.of(), 1));
        S3LifecyclePolicyInitializer initializer = new S3LifecyclePolicyInitializer(s3Client, awsProperties);

        LifecycleRule unmanagedRule = LifecycleRule.builder()
                .id("some-other-team-rule")
                .status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix("archive/").build())
                .expiration(LifecycleExpiration.builder().days(365).build())
                .build();
        LifecycleRule staleManagedRule = LifecycleRule.builder()
                .id("auto-cleanup-inputs")
                .status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix("inputs/").build())
                .expiration(LifecycleExpiration.builder().days(7).build()) // 과거에 다른 값으로 설정되어 있던 경우
                .build();

        when(s3Client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenReturn(GetBucketLifecycleConfigurationResponse.builder()
                        .rules(unmanagedRule, staleManagedRule)
                        .build());

        // when
        initializer.run(null);

        // then
        ArgumentCaptor<PutBucketLifecycleConfigurationRequest> captor =
                ArgumentCaptor.forClass(PutBucketLifecycleConfigurationRequest.class);
        verify(s3Client).putBucketLifecycleConfiguration(captor.capture());

        List<LifecycleRule> rules = captor.getValue().lifecycleConfiguration().rules();
        assertThat(rules).hasSize(2);
        assertThat(rules).anySatisfy(rule -> assertThat(rule.id()).isEqualTo("some-other-team-rule"));
        assertThat(rules)
                .filteredOn(rule -> rule.id().equals("auto-cleanup-inputs"))
                .singleElement()
                .satisfies(rule -> assertThat(rule.expiration().days()).isEqualTo(1)); // 최신 설정값으로 갱신됨
    }

    private AwsProperties awsProperties(AwsProperties.S3.Lifecycle lifecycle) {
        return new AwsProperties(
                new AwsProperties.S3(BUCKET, null, lifecycle),
                new AwsProperties.SageMaker("my-endpoint"),
                new AwsProperties.Sqs("my-queue")
        );
    }
}
