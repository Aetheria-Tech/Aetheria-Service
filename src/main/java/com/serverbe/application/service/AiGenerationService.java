package com.serverbe.application.service;

import com.serverbe.application.port.in.art.InitiateAiGenerationUseCase;
import com.serverbe.application.port.out.dto.geocoding.GeocodeResult;
import com.serverbe.application.port.out.geocode.GeocodePort;
import com.serverbe.application.port.out.sagemaker.SageMakerAsyncPort;
import com.serverbe.application.port.out.dto.ai.AiPromptCommand;
import com.serverbe.application.port.out.s3.S3AiInputPort;
import com.serverbe.application.port.out.task.TaskQueryPort;
import com.serverbe.application.port.out.task.TaskRateLimitPort;
import com.serverbe.application.port.out.task.TaskUpdatePort;
import com.serverbe.application.service.helper.AiTaskResourceCleaner;
import com.serverbe.domain.exception.ai.AiErrorCode;
import com.serverbe.domain.exception.ai.AiException;
import com.serverbe.domain.exception.external.ExternalApiClientException;
import com.serverbe.domain.exception.external.ExternalApiErrorCode;
import com.serverbe.domain.model.art.vo.Proficiency;
import com.serverbe.domain.model.task.AiTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


/**
 * AI 런닝 아트 생성 파이프라인을 오케스트레이션하는 핵심 비즈니스 서비스.
 * <p>
 * WebFlux(Reactor) 환경에서 동작하며, Redis, JPA 및 외부 AWS SDK(S3, SageMaker) 호출과 같은
 * <b>블로킹(Blocking) I/O 작업을 처리할 때 Netty 이벤트 루프가 차단되는 것을 방지하기 위해
 * {@link Schedulers#boundedElastic()} 스레드 풀로 작업을 완벽히 격리</b>하여 실행합니다.
 * 또한, 인프라 비용 최적화를 위해 다중 레이어의 Rate Limit 정책을 적용하여 중복 요청을 방어합니다.
 * </p>
 * <p>
 * <b>작업 상태 조회는 여기 없습니다.</b> {@link AiTaskStatusService}가 맡습니다 — DB에서 한 건
 * 읽어 반환하는 동기 조회라 이 사가와 협력자도 실행 모델도 공유하지 않습니다.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGenerationService implements InitiateAiGenerationUseCase {
    private final TaskQueryPort taskQueryPort;
    private final TaskUpdatePort taskUpdatePort;
    private final GeocodePort geocodePort;
    private final TaskRateLimitPort taskRateLimitPort;
    private final S3AiInputPort s3AiInputPort;
    private final SageMakerAsyncPort sageMakerAsyncPort;
    private final AiTaskResourceCleaner resourceCleaner;

    /**
     * 상태 전이(UPDATE) 구간을 하나의 트랜잭션으로 묶는 템플릿.
     * <p>
     * 트랜잭션 없이 {@code taskUpdatePort.save(...)} 를 호출하면 어댑터의 {@code findById} 가 자기 트랜잭션을
     * 열고 닫아 엔티티가 준영속이 되고, 이어지는 저장이 merge 를 유발해 <b>SELECT 두 번 + UPDATE 한 번</b>이
     * 나갑니다. 트랜잭션 안에서는 조회 결과가 관리 상태로 남아 merge 가 추가 조회 없이 끝납니다.
     * </p>
     * <p>
     * 신규 생성(INSERT) 경로에는 적용하지 않습니다. 그쪽은 {@code active_user_id} 유니크 위반을
     * {@code AiTaskPersistenceAdapter.save} 내부에서 잡아 {@code DUPLICATE_AI_REQUEST} 로 변환하는데,
     * 바깥 트랜잭션이 있으면 위반이 커밋 시점으로 밀려 그 catch 를 빠져나가기 때문입니다.
     * </p>
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * AI 생성 작업을 비동기 리액티브(Reactive) 파이프라인으로 시작합니다.
     * <p>
     * <b>파이프라인 실행 흐름:</b><br>
     * 1. <b>요청 자격 검증 (Rate Limit):</b> Redis 및 DB를 활용해 버튼 연타 및 중복 작업을 즉각적으로 차단합니다. (Fail-Fast)<br>
     * 2. <b>입력값 검증 (Geocoding):</b> 주소를 위경도로 변환하며 유효성을 검증합니다.<br>
     * 3. <b>DB 저장 (PENDING):</b> 검증을 통과하면 작업 초기 상태를 DB에 저장합니다.<br>
     * 4. <b>외부 연동 (S3 & SageMaker):</b> JSON 프롬프트를 생성하여 S3에 업로드하고, SageMaker 비동기 추론을 호출합니다.<br>
     * 5. <b>DB 업데이트 (PROCESSING):</b> 외부 호출이 성공하면 작업 상태를 처리 중으로 변경합니다.<br>
     * 6. <b>에러 핸들링 (FAILED):</b> 파이프라인 어느 곳에서든 예외 발생 시, 작업 상태를 실패로 기록합니다.
     * </p>
     */
    @Override
    public Mono<String> initiateGeneration(
            Long userId,
            String startPosition,
            String shape,
            Proficiency proficiency
    ) {
        return validateRequestEligibility(userId)
                .then(geocodePort.geocode(startPosition))
                .switchIfEmpty(Mono.error(new ExternalApiClientException(ExternalApiErrorCode.INVALID_ADDRESS, "해당 주소에 대한 검색 결과가 없습니다.")))
                .zipWhen(geocodeResult -> savePendingTask(userId, shape, proficiency))
                .flatMap(tuple -> {
                    GeocodeResult geocodeResult = tuple.getT1();
                    AiTask pendingTask = tuple.getT2();

                    return processExternalAiServices(pendingTask, geocodeResult, shape, proficiency)
                            // 외부 연동까지 성공한 뒤 DB 반영에 실패하면, 이미 만들어진 S3 자원이 고아로 남습니다.
                            .flatMap(processingTask -> saveProcessingTask(processingTask)
                                    .onErrorResume(e -> compensateAfterExternalSuccess(processingTask, e)))
                            .onErrorResume(e -> handlePipelineError(pendingTask, e));
                })
                .map(AiTask::id);
    }

    /**
     * AI 작업 요청에 대한 다중 레이어 방어(Rate Limiting & 중복 검증)를 수행합니다.
     * <p>
     * 외부 API 호출(Geocoding)이나 인프라(SageMaker) 비용이 발생하기 전에 악의적이거나
     * 실수로 인한 중복 요청을 가장 먼저 차단(Fail-Fast)합니다.
     * Redis 및 DB(JPA) 통신에 의한 블로킹을 방지하기 위해 {@link Schedulers#boundedElastic()}에서 실행됩니다.
     * <ul>
     * <li><b>1차 방어 (Redis):</b> 짧은 시간(예: 5초) 내의 반복적인 요청(따닥)을 인프라 레벨에서 차단합니다.</li>
     * <li><b>2차 방어 (DB):</b> 이미 처리 중(PENDING/PROCESSING)인 작업이 존재하는지 확인하여 비즈니스 1인 1작업 규칙을 강제합니다.</li>
     * </ul>
     * </p>
     *
     * @param userId 검증할 사용자의 ID
     * @return 검증에 성공하면 빈 Mono를 방출하고, 실패 시 적절한 {@link AiException}을 에러 스트림으로 방출
     */
    private Mono<Void> validateRequestEligibility(Long userId) {
        return Mono.fromRunnable(() -> {
            // 1차 방어: Redis 기반 5초 연타 방지 (따닥 방어)
            if (!taskRateLimitPort.tryLock(userId, 5)) {
                log.warn("[Rate Limit] 유저 {} 의 너무 잦은 요청 차단 (Redis)", userId);
                throw new AiException(AiErrorCode.RATE_LIMIT_EXCEEDED);
            }

            // 2차 방어: DB 기반 비즈니스 로직 체크 (진행 중인 작업 존재 여부)
            if (taskQueryPort.existsActiveTaskByUserId(userId)) {
                log.warn("[Rate Limit] 유저 {} 의 중복 작업 요청 차단 (DB)", userId);
                throw new AiException(AiErrorCode.DUPLICATE_AI_REQUEST);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * AI 작업의 초기 상태(PENDING)를 데이터베이스에 저장합니다.
     * <p>
     * JPA의 블로킹 I/O 작업이므로 {@link Schedulers#boundedElastic()} 스레드 풀로 격리하여 실행합니다.
     * </p>
     *
     * @param userId      요청한 사용자의 ID
     * @param shape       생성할 런닝 아트의 모양
     * @param proficiency 사용자의 러닝 숙련도
     * @return PENDING 상태로 저장된 {@link AiTask} 엔티티를 방출하는 Mono
     */
    private Mono<AiTask> savePendingTask(Long userId, String shape, Proficiency proficiency) {
        return Mono.fromCallable(() -> taskUpdatePort.save(AiTask.createPending(userId, shape, proficiency)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 외부 서비스(S3 및 SageMaker) 연동을 처리하는 리액티브 서브 파이프라인.
     * <p>
     * <b>Saga Pattern 적용:</b><br>
     * 분산 환경에서의 데이터 정합성을 유지하기 위해 S3 업로드(Step 1)와 SageMaker 호출(Step 2)을 분리합니다.
     * SageMaker 호출 단계에서 장애가 발생할 경우, `.onErrorResume`을 통해 즉각적으로
     * S3 파일 삭제를 지시하는 보상 트랜잭션({@link #compensateS3Upload})을 트리거합니다.
     * </p>
     * @param pendingTask   이전에 PENDING 상태로 DB에 저장된 작업 엔티티
     * @param geocodeResult 위치 검증이 완료된 위/경도 객체
     * @param shape         생성할 런닝 아트 모양
     * @param proficiency   사용자 숙련도
     * @return 처리가 정상 완료되어 PROCESSING 상태로 변경된 {@link AiTask} Mono 반환
     */
    private Mono<AiTask> processExternalAiServices(
            AiTask pendingTask,
            GeocodeResult geocodeResult,
            String shape,
            Proficiency proficiency
    ) {
        return Mono.fromCallable(() -> buildPromptCommand(geocodeResult, shape, proficiency))

                // Step 1: S3 업로드
                .flatMap(prompt -> Mono.fromCallable(() -> s3AiInputPort.uploadInputJson(pendingTask.id(), prompt))
                        .subscribeOn(Schedulers.boundedElastic()))

                // Step 2: SageMaker 호출 및 보상 트랜잭션 연동
                .flatMap(inputS3Uri -> Mono.fromCallable(() -> sageMakerAsyncPort.invokeAsync(pendingTask.id(), inputS3Uri))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(outputS3Uri -> pendingTask.markAsProcessing(inputS3Uri, outputS3Uri))
                        // 🛡️ [보상 로직] SageMaker 호출에서 예외가 발생하면 S3 파일 삭제를 시도합니다.
                        .onErrorResume(e -> compensateS3Upload(inputS3Uri, e))
                );
    }

    /**
     * SageMaker 호출 실패 시 S3에 선행 업로드된 고아(Orphan) 파일을 제거하는 보상 트랜잭션.
     * <p>
     * <b>에러 전파 정책:</b><br>
     * 보상 로직(S3 삭제) 자체에 실패하더라도 메인 파이프라인의 에러 흐름을 방해하지 않도록,
     * 삭제 에러는 삼키고(로그 기록) 최초 발생했던 SageMaker 원본 에러를 하위 스트림으로 다시 방출합니다.
     * </p>
     * @param inputS3Uri    보상 로직에 의해 삭제되어야 할 S3 파일 경로
     * @param originalError SageMaker 호출 중 발생하여 보상 로직을 트리거한 원본 예외 객체
     * @return 원본 예외를 포함한 에러 스트림({@code Mono.error()})
     */
    private Mono<AiTask> compensateS3Upload(String inputS3Uri, Throwable originalError) {
        log.warn("[Compensation] SageMaker 호출 실패. S3 찌꺼기 파일 삭제 시도: {}", inputS3Uri);

        return Mono.fromRunnable(() -> s3AiInputPort.deleteInputFile(inputS3Uri))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(deleteError -> {
                    // 보상 로직(S3 삭제)마저 실패할 최악의 경우, 로그만 남기고 원본 에러를 우선시합니다.
                    log.error("[Compensation Error] S3 파일 삭제 실패 (수동 정리 필요): {}", inputS3Uri, deleteError);
                    return Mono.empty();
                })
                // 삭제가 성공하든 실패하든, 결국 작업은 실패한 것이므로 원래 발생했던 예외를 다시 던집니다.
                .then(Mono.error(originalError));
    }

    /**
     * S3 업로드와 SageMaker 호출까지 모두 성공한 뒤 DB 반영 단계에서 실패했을 때의 보상 트랜잭션.
     * <p>
     * 이 시점에는 입력 프롬프트가 S3에 올라가 있고 추론도 이미 시작된 상태입니다. 작업은 실패로 종결될 것이므로
     * 입력 파일은 즉시 삭제합니다. 다만 추론 결과물은 <b>아직 생성되지 않았다가 우리가 지운 뒤에 기록될 수 있어</b>
     * 여기서 완전히 정리되지 않습니다. 그 고아 결과물은 나중에 SQS 콜백이 도착했을 때
     * 이미 종결된 작업임을 확인하는 경로에서 정리됩니다.
     * </p>
     * <p>
     * 정리 성공 여부와 무관하게, 최초에 발생한 원본 예외를 그대로 다시 방출하여 상위 에러 처리로 넘깁니다.
     * </p>
     *
     * @param processingTask S3/SageMaker URI가 채워진 작업 엔티티
     * @param originalError  DB 반영 중 발생한 원본 예외
     * @return 원본 예외를 포함한 에러 스트림
     */
    private Mono<AiTask> compensateAfterExternalSuccess(AiTask processingTask, Throwable originalError) {
        log.warn("[Compensation] PROCESSING 상태 저장 실패. S3 임시 자원 정리 시도 - TaskID: {}", processingTask.id());

        return Mono.fromRunnable(() -> resourceCleaner.cleanUp(processingTask))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.error(originalError));
    }

    /**
     * 처리 중(PROCESSING) 상태로 상태가 변경된 AI 작업 정보를 데이터베이스에 반영합니다.
     * <p>
     * JPA 블로킹 I/O 작업이므로 {@link Schedulers#boundedElastic()} 스레드 풀에서 실행됩니다.
     * </p>
     *
     * @param processingTask S3 URI 및 SageMaker 반환값이 포함된 AI 작업 엔티티
     * @return 데이터베이스에 반영된 {@link AiTask} 엔티티를 방출하는 Mono
     */
    private Mono<AiTask> saveProcessingTask(AiTask processingTask) {
        return Mono.fromCallable(() -> transactionTemplate.execute(status -> taskUpdatePort.save(processingTask)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * AI 파이프라인 처리 중 발생한 예외를 처리하고 작업 상태를 실패(FAILED)로 기록합니다.
     * <p>
     * 에러 로그를 남기고 DB에 실패 상태 및 사유를 반영한 뒤, 클라이언트에게 반환할
     * 최종 비즈니스 예외({@link AiException})를 발생시킵니다.
     * </p>
     *
     * @param aiTask 예외가 발생한 시점의 AI 작업 엔티티
     * @param e      파이프라인에서 발생한 원본 예외
     * @return 에러 파이프라인으로 전환되어 최종적으로 {@link AiException}을 방출하는 Mono
     */
    private Mono<AiTask> handlePipelineError(AiTask aiTask, Throwable e) {
        log.error("[AI Pipeline Error] 요청 처리 중 오류 발생 - TaskID: {}", aiTask.id(), e);

        return Mono.fromCallable(() -> {
                    AiTask failedTask = aiTask.markAsFailed(e.getMessage());
                    return transactionTemplate.execute(status -> taskUpdatePort.save(failedTask));
                })
                .subscribeOn(Schedulers.boundedElastic())
                // DB 기록마저 실패할 경우 예외를 먹어치우고(Log만 남김) 원래의 비즈니스 예외만 던지도록 처리
                .onErrorResume(dbError -> {
                    log.error("[AI Pipeline Error] 실패 상태 기록 중 DB 2차 오류 발생 - TaskID: {}", aiTask.id(), dbError);
                    return Mono.empty();
                })
                .then(Mono.error(new AiException(AiErrorCode.AI_PIPELINE_ERROR)));
    }

    /**
     * AI 모델(SageMaker)에게 전달할 요청 파라미터를 조립합니다.
     *
     * @param geocodeResult 런닝 시작 위치
     * @param shape         목표 아트 모양
     * @param proficiency   러닝 숙련도
     * @return 추론 요청 파라미터
     * @implNote 값이 비었을 때의 기본값(모양은 빈 문자열, 숙련도는 {@code BEGINNER})은 직렬화가 아니라
     * <b>애플리케이션 정책</b>이므로 어댑터가 아니라 여기 남습니다. 직렬화는
     * {@code adapter.out.external.s3.S3AiInputAdapter}가 합니다.
     */
    private AiPromptCommand buildPromptCommand(
            GeocodeResult geocodeResult,
            String shape,
            Proficiency proficiency
    ) {
        return new AiPromptCommand(
                geocodeResult.latitude(),
                geocodeResult.longitude(),
                shape != null ? shape : "",
                proficiency != null ? proficiency.name() : Proficiency.BEGINNER.name()
        );
    }
}