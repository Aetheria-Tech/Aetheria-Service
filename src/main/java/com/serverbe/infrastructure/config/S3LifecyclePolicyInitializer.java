package com.serverbe.infrastructure.config;

import com.serverbe.infrastructure.config.properties.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 애플리케이션 기동 시, AI 파이프라인의 임시 자원(Input JSON 등)이 쌓여 발생하는
 * 불필요한 S3 스토리지 비용을 막기 위한 Lifecycle Rule을 버킷에 자동으로 적용하는 컴포넌트.
 * <p>
 * <b>동작 방식:</b><br>
 * {@code aws.s3.lifecycle.enabled} 값이 {@code true}일 때만 동작하며(운영 버킷의
 * 의도치 않은 변경을 막기 위한 기본값은 {@code false}), {@code tempPrefixes}에 해당하는
 * 경로의 객체를 생성 {@code expirationDays}일 후 자동 만료(삭제)시키는 규칙을 멱등적으로 적용합니다.
 * IA 등으로의 스토리지 클래스 전환 규칙은 추가하지 않아 항상 Standard 클래스를 유지합니다.
 * (IA는 최소 보관 기간 30일 페널티가 있어, 1일 만에 삭제되는 임시 자원에는 오히려 손해이기 때문입니다.)
 * </p>
 * <p>
 * S3의 {@code PutBucketLifecycleConfiguration} API는 버킷 전체의 Lifecycle 설정을
 * 통째로 덮어쓰는 방식이므로, 기존에 버킷에 설정되어 있던(우리가 관리하지 않는) 규칙은
 * 먼저 조회하여 보존하고, 이 컴포넌트가 관리하는 규칙만 갱신합니다.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3LifecyclePolicyInitializer implements ApplicationRunner {

    /** 이 컴포넌트가 생성/관리하는 규칙임을 식별하기 위한 Rule ID 접두사 (재기동 시 멱등하게 갱신하기 위함). */
    private static final String MANAGED_RULE_ID_PREFIX = "auto-cleanup-";

    private final S3Client s3Client;
    private final AwsProperties awsProperties;

    @Override
    public void run(ApplicationArguments args) {
        AwsProperties.S3.Lifecycle lifecycle = awsProperties.s3().lifecycle();

        if (lifecycle == null || !lifecycle.enabled()) {
            log.debug("[S3 Lifecycle] 자동 적용이 비활성화되어 있습니다 (aws.s3.lifecycle.enabled=false). 필요 시 AWS 콘솔에서 수동 설정하세요.");
            return;
        }

        // 입력 버킷: 우리가 직접 업로드하는 프롬프트 JSON 등의 임시 자원
        applyQuietly(awsProperties.s3().aiInputBucket(), lifecycle.tempPrefixes(), lifecycle.expirationDays());

        // 출력 버킷: SageMaker가 기록하는 추론 결과물. 삭제 실패분과 고아 결과물의 최종 방어선입니다.
        applyQuietly(awsProperties.s3().aiOutputBucket(), lifecycle.outputPrefixes(), lifecycle.expirationDays());
    }

    /**
     * 지정한 버킷에 만료 규칙을 적용하되, 실패해도 애플리케이션 기동을 막지 않습니다.
     * 버킷 이름이 설정되지 않았다면 조용히 건너뜁니다.
     */
    private void applyQuietly(String bucket, List<String> prefixes, int expirationDays) {
        if (bucket == null || bucket.isBlank()) {
            log.debug("[S3 Lifecycle] 버킷 이름이 설정되지 않아 정책 적용을 건너뜁니다.");
            return;
        }
        if (prefixes == null || prefixes.isEmpty()) {
            log.debug("[S3 Lifecycle] 버킷 [{}]에 적용할 경로가 없어 정책 적용을 건너뜁니다.", bucket);
            return;
        }

        try {
            applyManagedRules(bucket, prefixes, expirationDays);
        } catch (Exception e) {
            // Lifecycle 정책 적용 실패가 애플리케이션 기동 자체를 막아서는 안 되므로, 에러를 삼키고 로그만 남깁니다.
            log.error("[S3 Lifecycle] 버킷 [{}]에 Lifecycle 정책 적용 실패. 비용 방어를 위해 AWS 콘솔에서 수동 확인이 필요합니다.", bucket, e);
        }
    }

    private void applyManagedRules(String bucket, List<String> prefixes, int expirationDays) {
        List<LifecycleRule> existingRules = fetchExistingRules(bucket);

        // 우리가 관리하지 않는(다른 목적으로 설정된) 규칙은 그대로 보존합니다.
        List<LifecycleRule> preservedRules = existingRules.stream()
                .filter(rule -> !rule.id().startsWith(MANAGED_RULE_ID_PREFIX))
                .toList();

        List<LifecycleRule> managedRules = prefixes.stream()
                .map(prefix -> buildExpirationRule(prefix, expirationDays))
                .toList();

        List<LifecycleRule> mergedRules = new ArrayList<>(preservedRules);
        mergedRules.addAll(managedRules);

        s3Client.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
                .bucket(bucket)
                .lifecycleConfiguration(BucketLifecycleConfiguration.builder()
                        .rules(mergedRules)
                        .build())
                .build());

        log.info("[S3 Lifecycle] 버킷 [{}]의 임시 경로 {}에 '생성 {}일 후 만료(Standard 클래스 유지)' 정책 적용 완료",
                bucket, prefixes, expirationDays);
    }

    /**
     * 버킷에 기존 설정된 Lifecycle 규칙을 조회합니다. 설정이 전혀 없는 최초 상태(404)라면 빈 목록을 반환합니다.
     */
    private List<LifecycleRule> fetchExistingRules(String bucket) {
        try {
            GetBucketLifecycleConfigurationResponse response = s3Client.getBucketLifecycleConfiguration(
                    GetBucketLifecycleConfigurationRequest.builder().bucket(bucket).build());
            return response.hasRules() ? new ArrayList<>(response.rules()) : new ArrayList<>();
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() == 404) {
                return new ArrayList<>();
            }
            throw e;
        }
    }

    private LifecycleRule buildExpirationRule(String prefix, int expirationDays) {
        return LifecycleRule.builder()
                .id(MANAGED_RULE_ID_PREFIX + prefix.replace("/", ""))
                .status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix(prefix).build())
                .expiration(LifecycleExpiration.builder().days(expirationDays).build())
                .build();
    }
}
