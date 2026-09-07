package com.serverbe.application.service;

import com.serverbe.application.port.in.art.RegisterCompletedArtUseCase;
import com.serverbe.application.port.in.dto.art.AiGenerationResultDto;
import com.serverbe.application.port.in.task.RetrieveAiResultUseCase;
import com.serverbe.application.port.out.notification.TaskNotificationPort;
import com.serverbe.application.port.out.s3.S3AiOutputPort;
import com.serverbe.application.port.out.task.TaskUpdatePort;
import com.serverbe.application.service.helper.AiTaskResourceCleaner;
import com.serverbe.domain.model.task.AiTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI 작업(Task)의 결과물을 조회하고 후속 처리를 담당하는 비즈니스 서비스 구현체.
 * <p>
 * 외부 네트워크 I/O(S3 연동, SSE 알림) 작업 시 DB 커넥션 풀 고갈을 방지하기 위해
 * 의도적으로 선언적 트랜잭션(@Transactional)을 배제하고,
 * TransactionTemplate을 사용하여 DB 등록 과정만 프로그래밍 방식으로 원자성을 보장합니다.
 * </p>
 * <p>
 * <b>호출 맥락에 대한 주의:</b><br>
 * 이 유스케이스를 호출하는 쪽이 비관적 락을 유지하기 위해 트랜잭션을 열어 둡니다
 * (지금은 {@link AiNotificationService#handleNotification}).
 * {@link TransactionTemplate}의 기본 전파 속성은 {@code REQUIRED}이므로 {@link #handleSuccess}의 트랜잭션은
 * <b>그 바깥 트랜잭션에 합류</b>합니다. 따라서 그 안에서 예외가 발생하면 바깥 트랜잭션이 rollback-only로 마킹되고,
 * 이후의 어떤 DB 쓰기도 최종적으로 롤백됩니다. 실패를 기록하는 {@link #handleFailure}가 바로 그 함정에 빠지므로,
 * 실패 기록만은 별도의 신규 트랜잭션({@code REQUIRES_NEW})에서 수행해 반드시 살아남도록 분리했습니다.
 * </p>
 */
@Slf4j
@Service
public class AiResultRetrievalService implements RetrieveAiResultUseCase {

    private final TaskUpdatePort taskUpdatePort;
    private final S3AiOutputPort s3AiOutputPort;
    private final TaskNotificationPort taskNotificationPort;
    private final RegisterCompletedArtUseCase registerCompletedArtUseCase;
    private final AiTaskResourceCleaner resourceCleaner;

    /** 성공 경로의 DB 쓰기를 원자적으로 묶는 템플릿. 바깥 트랜잭션이 있으면 합류합니다. */
    private final TransactionTemplate transactionTemplate;

    /**
     * 실패 기록 전용 템플릿. 바깥 트랜잭션이 rollback-only로 마킹된 뒤에도 FAILED 상태가 확실히 커밋되도록
     * {@code REQUIRES_NEW}로 분리합니다.
     */
    private final TransactionTemplate failureTransactionTemplate;

    public AiResultRetrievalService(
            TaskUpdatePort taskUpdatePort,
            S3AiOutputPort s3AiOutputPort,
            TaskNotificationPort taskNotificationPort,
            RegisterCompletedArtUseCase registerCompletedArtUseCase,
            AiTaskResourceCleaner resourceCleaner,
            TransactionTemplate transactionTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.taskUpdatePort = taskUpdatePort;
        this.s3AiOutputPort = s3AiOutputPort;
        this.taskNotificationPort = taskNotificationPort;
        this.registerCompletedArtUseCase = registerCompletedArtUseCase;
        this.resourceCleaner = resourceCleaner;
        this.transactionTemplate = transactionTemplate;

        this.failureTransactionTemplate = new TransactionTemplate(transactionManager);
        this.failureTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void processTaskResult(AiTask aiTask) {
        s3AiOutputPort.downloadOutput(aiTask.outputS3Uri()).ifPresent(resultDto -> {
            log.info("[Result Retrieval] 결과물 감지 - TaskID: {}", aiTask.id());

            try {
                handleSuccess(aiTask, resultDto);
            } catch (Exception e) {
                handleFailure(aiTask, e);
            }
        });
    }

    /**
     * 트랜잭션 내에서 처리할 반환값을 묶기 위한 레코드
     */
    private record ProcessResult(Long savedArtId, AiTask updatedTask) {
    }

    /**
     * 성공 파이프라인: 데이터 등록 -> 상태 갱신 -> 임시 파일 삭제 -> 클라이언트 알림
     */
    private void handleSuccess(AiTask aiTask, AiGenerationResultDto resultDto) {

        // 1. 핵심 비즈니스 로직 (TransactionTemplate으로 원자성 보장)
        // 여기서 에러가 나면 saveArt와 updateTask가 함께 롤백되고 Exception이 던져집니다.
        ProcessResult processResult = transactionTemplate.execute(status -> {
            Long artId = saveArt(aiTask, resultDto);
            AiTask task = updateTask(aiTask, artId);
            return new ProcessResult(artId, task);
        });

        log.info("[Result Retrieval] Task 최종 상태 DB 저장 완료 - TaskID: {}", processResult.updatedTask().id());

        // 2. 부가 로직 (외부 I/O이므로 트랜잭션 블록 밖에서 안전하게 실행)
        resourceCleaner.cleanUp(processResult.updatedTask());
        sendCompletionNotification(processResult.updatedTask(), processResult.savedArtId());
    }

    /**
     * 실패 파이프라인: 결과물 등록 도중 예외가 발생했을 때 상태를 FAILED로 확정하고 클라이언트에 알립니다.
     * <p>
     * 상태 갱신을 신규 트랜잭션({@code REQUIRES_NEW})에서 수행하는 것이 핵심입니다.
     * 바깥 트랜잭션(호출하는 쪽이 연 것)은 이미 rollback-only로 마킹되어 있을 수 있어, 그 트랜잭션에 합류해 저장하면
     * 커밋 시점에 전부 롤백되어 <b>실패가 아무 데도 기록되지 않은 채 사라집니다.</b>
     * </p>
     * <p>
     * S3 임시 자원 정리도 함께 수행합니다. 결과물 등록에 실패했더라도 입력 프롬프트와 추론 결과물은
     * S3에 그대로 남아 비용이 계속 발생하기 때문입니다.
     * </p>
     */
    private void handleFailure(AiTask aiTask, Exception e) {
        log.error("[Result Retrieval Error] 결과물 등록 중 오류 발생 - TaskID: {}", aiTask.id(), e);

        AiTask failedTask = aiTask.markAsFailed("데이터 등록 실패: " + e.getMessage());
        failureTransactionTemplate.execute(status -> taskUpdatePort.save(failedTask));

        resourceCleaner.cleanUp(failedTask);
        taskNotificationPort.notifyTaskFailed(failedTask.id(), "런닝 아트 등록 중 오류가 발생했습니다.");
    }

    private Long saveArt(AiTask aiTask, AiGenerationResultDto resultDto) {
        return registerCompletedArtUseCase.registerFromPolyline(
                aiTask.userId(),
                resultDto.gpx(),
                aiTask.shape() + " 코스 (" + aiTask.proficiency().getProficiency() + ")",
                aiTask.shape(),
                aiTask.proficiency()
        );
    }

    private AiTask updateTask(AiTask aiTask, Long savedArtId) {
        AiTask updatedTask = aiTask.markAsCompleted(savedArtId);
        taskUpdatePort.save(updatedTask);
        return updatedTask;
    }

    private void sendCompletionNotification(AiTask updatedTask, Long savedArtId) {
        try {
            taskNotificationPort.notifyTaskCompleted(updatedTask.id(), String.valueOf(savedArtId));
            log.info("[Result Retrieval] 파이프라인 완수 및 알림 발송 성공 - TaskID: {}, ArtID: {}", updatedTask.id(), savedArtId);
        } catch (Exception e) {
            log.error("[Result Retrieval Error] DB 저장은 완료되었으나 SSE 알림 발송에 실패했습니다. - TaskID: {}", updatedTask.id(), e);
        }
    }
}
