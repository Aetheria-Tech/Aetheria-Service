package com.serverbe.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * application.yml 파일의 'aws' 하위 설정값들을
 * 타입 세이프(Type-safe)하게 자바 객체로 매핑해주는 Record 클래스입니다.
 */
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
        S3 s3,                  // AWS S3 관련 설정 그룹
        SageMaker sagemaker,    // AWS SageMaker 관련 설정 그룹
        Sqs sqs
) {
    /**
     * S3 스토리지 관련 설정
     * 매핑 경로: aws.s3.*
     */
    public record S3(
            /*
             * AI 모델이 사용할 데이터가 저장되는 S3 버킷의 실제 이름입니다.
             * (예: my-project-ai-inference-input-bucket)
             * YAML 프로퍼티: aws.s3.ai-input-bucket
             */
            String aiInputBucket,

            /*
             * SageMaker 비동기 추론 결과물이 저장되는 S3 버킷의 이름입니다.
             * 애플리케이션이 직접 업로드하지는 않지만, 삭제에 실패한 결과물이 방치되지 않도록
             * Lifecycle 정책의 적용 대상으로 삼기 위해 필요합니다. 비워두면 정책을 적용하지 않습니다.
             * YAML 프로퍼티: aws.s3.ai-output-bucket
             */
            String aiOutputBucket,

            /*
             * 임시 자원(Input JSON 등) 자동 정리를 위한 S3 Lifecycle 정책 설정입니다.
             * 매핑 경로: aws.s3.lifecycle.*
             */
            Lifecycle lifecycle
    ) {
        /**
         * S3 임시 자원 자동 클린업(Lifecycle Rule) 설정.
         * <p>
         * 타임아웃된 좀비 태스크나 임시 Input JSON 파일들이 S3에 쌓여 불필요한 비용이
         * 발생하는 것을 막기 위해, {@code tempPrefixes}에 해당하는 객체들을
         * 생성 후 {@code expirationDays}일이 지나면 자동 만료(삭제)시킵니다.
         * <p>
         * IA(Infrequent Access) 등으로의 스토리지 클래스 전환(Transition) 규칙은 의도적으로
         * 추가하지 않습니다. IA는 최소 보관 기간 30일 페널티가 있어, 1일 만에 삭제되는
         * 임시 자원에 적용하면 오히려 비용 손해이기 때문입니다. (Standard 클래스 유지)
         */
        public record Lifecycle(
                /*
                 * 정책 자동 적용 여부. 운영 버킷을 의도치 않게 변경하지 않도록 기본값은 false이며,
                 * 명시적으로 true로 설정해야 애플리케이션 기동 시 정책이 적용됩니다.
                 * YAML 프로퍼티: aws.s3.lifecycle.enabled
                 */
                @DefaultValue("false") boolean enabled,

                /*
                 * 만료 대상이 되는 임시 경로(Object Key Prefix) 목록입니다.
                 * (예: "inputs/", "temp/")
                 * YAML 프로퍼티: aws.s3.lifecycle.temp-prefixes
                 */
                @DefaultValue({"inputs/", "temp/"}) List<String> tempPrefixes,

                /*
                 * 결과물 버킷(aws.s3.ai-output-bucket)에서 만료 대상이 되는 경로 목록입니다.
                 * 추론 결과물은 DB 등록 직후 즉시 삭제되지만, 삭제가 실패하거나 작업이 종결된 뒤에
                 * 뒤늦게 기록된 고아 결과물이 남을 수 있어 최종 방어선이 필요합니다.
                 * YAML 프로퍼티: aws.s3.lifecycle.output-prefixes
                 */
                @DefaultValue({"outputs/"}) List<String> outputPrefixes,

                /*
                 * 객체 생성 후 만료(삭제)까지의 일수입니다.
                 * YAML 프로퍼티: aws.s3.lifecycle.expiration-days
                 */
                @DefaultValue("1") int expirationDays
        ) {}
    }

    /**
     * SageMaker AI 모델 서버 관련 설정
     * 매핑 경로: aws.sagemaker.*
     */
    public record SageMaker(
            /*
             * 비동기 추론(Async Inference) 요청을 보낼 대상 SageMaker 엔드포인트의 이름입니다.
             * (예: my-project-async-inference-endpoint)
             * YAML 프로퍼티: aws.sagemaker.endpoint-name
             */
            String endpointName
    ) {}

    /**
     * SQS 관련 설정
     * 매핑 경로: aws.sqs.*
     */
    public record Sqs(
            /*
             * AI 작업 완료 알림을 수신할 SQS 큐의 이름입니다.
             * YAML 프로퍼티: aws.sqs.ai-notification-queue-name
             */
            String aiNotificationQueueName
    ) {}
}