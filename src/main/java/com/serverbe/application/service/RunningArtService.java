package com.serverbe.application.service;

import com.serverbe.application.port.dto.PageQuery;
import com.serverbe.application.port.dto.PageResult;
import com.serverbe.application.port.in.art.DeleteRunningArtUseCase;
import com.serverbe.application.port.in.art.GetRunningArtUseCase;
import com.serverbe.application.port.in.art.UpdateRunningArtUseCase;
import com.serverbe.application.port.in.dto.art.RunningArtResult;
import com.serverbe.application.port.in.dto.art.RunningArtUpdateCommand;
import com.serverbe.application.port.out.art.RunningArtRedisPort;
import com.serverbe.application.port.out.jpa.RunningArtRepositoryPort;
import com.serverbe.domain.exception.BusinessException;
import com.serverbe.domain.exception.art.ArtErrorCode;
import com.serverbe.domain.exception.art.ArtException;
import com.serverbe.domain.model.art.RunningArt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * @responsibility 사용자가 생성한 런닝 아트의 조회·수정·삭제를 처리하며, 그 과정에서 접근 권한을 제어합니다.
 * @implSpec 세 유스케이스({@link GetRunningArtUseCase}, {@link UpdateRunningArtUseCase},
 * {@link DeleteRunningArtUseCase})가 한 클래스에 있는 것은 개수 때문이 아니라 <b>{@link #findAndVerifyOwner}를
 * 공유하기 때문</b>입니다. 셋 다 "이 아트가 요청자의 것인가"를 먼저 묻고 시작합니다.
 * @implNote 위치 기반 탐색({@link RunningArtSearchService})과 AI 결과 등록
 * ({@link RunningArtRegistrationService})은 <b>여기 없습니다.</b> 전자는 소유권 개념이 없는 리액티브
 * 탐색이고 후자는 호출자가 사용자가 아니라 내부 흐름이라, 협력자도 실행 모델도 이 셋과 공유하지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunningArtService implements
        GetRunningArtUseCase,
        DeleteRunningArtUseCase,
        UpdateRunningArtUseCase
{

    private final RunningArtRepositoryPort repositoryPort;
    private final RunningArtRedisPort runningArtRedisPort;

    /**
     * @param userId    런닝 아트를 조회할 사용자의 고유 식별자
     * @param pageQuery 페이징·정렬 정보
     * @return 사용자의 런닝 아트 정보 페이지
     * @requirement UC-ART-03: 사용자의 전체 런닝 아트 조회 요청
     * @responsibility 특정 사용자가 보유한 모든 런닝 아트 목록을 조회합니다.
     * @implSpec 1. {@link RunningArtRepositoryPort#findByUserId(Long, PageQuery)}를 통해 해당 유저의 엔티티 목록을 획득합니다.<br>
     * 2. {@link PageResult#map(java.util.function.Function)}을 활용하여 도메인 모델을 {@link RunningArtResult} DTO로 일괄 변환합니다.
     * @implNote 현재 순수 JPA로 구현되어 있으며, 대량 데이터 조회 시 성능 최적화가 필요할 경우 Querydsl Projection 도입을 고려해야 합니다.
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<RunningArtResult> getRunningArtsByUserId(Long userId, PageQuery pageQuery) {
        return repositoryPort.findByUserId(userId, pageQuery).map(RunningArtResult::toResult);
    }

    /**
     * @param userId       사용자의 고유 식별자 (소유권 검증용)
     * @param runningArtId 조회할 런닝 아트의 고유 식별자
     * @return 조회된 런닝 아트 상세 데이터 {@link RunningArtResult}
     * @requirement UC-ART-02: 런닝 아트 단건 조회 요청
     * @responsibility 특정 런닝 아트의 상세 정보를 조회하며, 요청자에게 소유권이 있는지 검증합니다.
     * @implSpec {@link #findAndVerifyOwner(Long, Long)}를 호출하여 데이터 존재 여부와 접근 권한을 동시에 확인합니다.
     */
    @Override
    @Transactional(readOnly = true)
    public RunningArtResult getRunningArtById(Long userId, Long runningArtId) {
        return RunningArtResult.toResult(findAndVerifyOwner(userId, runningArtId));
    }

    /**
     * @param userId       수정 요청자의 고유 식별자
     * @param runningArtId 수정 대상 런닝 아트의 고유 식별자
     * @param command      수정한 메타데이터 정보가 담긴 DTO
     * @requirement UC-ART-04: 런닝 아트 수정 요청
     * @responsibility 런닝 아트의 메타데이터(제목, 설명 등)를 수정합니다.
     * @implSpec 1. {@link #findAndVerifyOwner(Long, Long)}로 권한을 확인합니다.<br>
     * 2. {@link RunningArtRepositoryPort#updateMetadata(Long, RunningArtUpdateCommand)}를 호출하여 변경 사항을 영속성 계층에 반영합니다.
     */
    @Override
    @Transactional
    public void updateRunningArt(Long userId, Long runningArtId, RunningArtUpdateCommand command) {
        findAndVerifyOwner(userId, runningArtId);

        repositoryPort.updateMetadata(runningArtId, command);
    }

    /**
     * @param userId       삭제 요청자의 고유 식별자
     * @param runningArtId 삭제할 런닝 아트의 고유 식별자
     * @requirement UC-ART-05 런닝 아트 삭제 요청
     * @responsibility 특정 런닝 아트를 영구 삭제합니다.
     * @implSpec {@link #findAndVerifyOwner(Long, Long)}를 통해 본인의 기록임이 확인된 경우에만 삭제 명령을 수행합니다.
     * @implSpec {@link TransactionSynchronizationManager}를 사용하여 커밋이 성공한 후에만 Redis에서 GEO 데이터가 등록되도록
     */
    @Override
    @Transactional
    public void deleteRunningArt(Long userId, Long runningArtId) {
        findAndVerifyOwner(userId, runningArtId);

        repositoryPort.deleteById(runningArtId);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runningArtRedisPort.removeLocation(runningArtId)
                        .subscribe(
                                result -> log.info("Redis GEO 삭제 완료 (ArtId: {})", runningArtId),
                                error -> log.error("Redis GEO 삭제 실패 (ArtId: {}): {}", runningArtId, error.getMessage())
                        );
            }
        });
    }

    /**
     * @param userId 모든 데이터를 삭제할 사용자의 고유 식별자
     * @requirement UC-ART-06: 사용자 전체 런닝 아트 삭제 요청
     * @responsibility 특정 사용자와 연관된 모든 런닝 아트 데이터를 삭제합니다.
     * @implSpec {@link TransactionSynchronizationManager}를 사용하여 커밋이 성공한 후에만 Redis에서 GEO 데이터가 삭제되도록 함.
     * @implNote 유일한 호출자는 사용자가 직접 여는 {@code DELETE /api/v1/running-arts/me}입니다.
     * <b>회원 탈퇴는 이 경로를 타지 않습니다</b> — {@code UserDataCleanupManager}가
     * {@link RunningArtRepositoryPort#deleteByUserId(Long)}를 직접 부르므로, 탈퇴 시에는 아래 Redis
     * 정리가 실행되지 않아 GEO 항목이 고아로 남습니다. 그 경로를 이 유스케이스로 돌리는 것은
     * 동작 변경이라 별도 항목으로 다룹니다.
     */
    @Override
    @Transactional
    public void deleteAllRunningArtsByUserId(Long userId) {
        // 1. 삭제할 모든 ID를 먼저 조회 (Redis 삭제를 위해 필요)
        List<Long> artIdsToDelete = repositoryPort.findIdsByUserId(userId);

        if (artIdsToDelete.isEmpty()) return;

        // 2. DB에서 전체 삭제 (Blocking)
        repositoryPort.deleteByUserId(userId);

        // 3. 트랜잭션 커밋 후 Redis에서 비동기 삭제
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Flux.fromIterable(artIdsToDelete)
                        .flatMap(runningArtRedisPort::removeLocation, 10)
                        .subscribe(
                                null, // 결과 로그는 생략 가능
                                error -> log.error("Redis 일괄 삭제 중 오류 발생 (UserId: {}): {}", userId, error.getMessage()),
                                () -> log.info("유저(ID: {})의 모든 Redis GEO 데이터 삭제 완료", userId)
                        );
            }
        });
    }

    /**
     * @param userId       검증할 사용자의 ID
     * @param runningArtId 검증 대상 런닝 아트 ID
     * @return 검증이 완료된 {@link RunningArt} 엔티티
     * @throws BusinessException 데이터가 없거나 소유권이 없는 경우 발생
     * @responsibility 데이터의 존재 유무를 확인하고, 요청한 사용자가 해당 데이터의 실제 소유자인지 검증합니다.
     * @implSpec 1. ID로 엔티티를 조회하며, 부재 시 {@link ArtErrorCode#NOT_FOUND_RUNNING_ART} 예외를 던집니다.<br>
     * 2. 조회된 엔티티의 {@code userId} 필드와 요청자의 {@code userId}를 비교하여 불일치 시 {@link ArtErrorCode#USER_IS_NOT_OWNER_OF_RUNNING_ART} 예외를 던집니다.
     * @implNote - 이 메서드는 도메인 엔티티({@link RunningArt})를 직접 반환하므로 서비스 내부 전용(private)으로만 사용해야 합니다.<br>
     * - 더티 체킹이나 연관 관계 접근을 위해 반드시 {@link Transactional} 컨텍스트 내에서 호출되어야 합니다.
     */
    private RunningArt findAndVerifyOwner(Long userId, Long runningArtId) {
        RunningArt runningArt = repositoryPort.findById(runningArtId)
                .orElseThrow(() -> new ArtException(
                        ArtErrorCode.NOT_FOUND_RUNNING_ART,
                        String.format("런닝아트(%d)를 찾지 못했습니다.", runningArtId))
                );
        if (!runningArt.userId().equals(userId)) {
            throw new ArtException(
                    ArtErrorCode.USER_IS_NOT_OWNER_OF_RUNNING_ART,
                    String.format("사용자(ID: %d)는 해당 런닝아트(ID: %d)에 대한 권한이 없습니다.", userId, runningArtId)
            );
        }
        return runningArt;
    }
}
