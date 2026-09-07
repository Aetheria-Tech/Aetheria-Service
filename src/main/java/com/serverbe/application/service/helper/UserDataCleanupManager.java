package com.serverbe.application.service.helper;

import com.serverbe.application.port.out.jpa.RunningArtRepositoryPort;
import com.serverbe.application.port.out.jpa.UserRepositoryPort;
import com.serverbe.application.port.out.token.RefreshTokenSessionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * @author Duskafka
 * @responsibility 사용자와 연관된 모든 도메인 데이터(활동 기록, 계정 정보, 보안 세션)를 원자적으로 파기합니다.
 * @implSpec 1. <b>Hard Delete</b>: 법적/정책적 근거에 따라 사용자의 데이터를 데이터베이스 및 캐시에서 영구 삭제합니다.<br>
 * 2. <b>Transaction Boundary</b>: {@link Transactional}을 통해 여러 리포지토리의 삭제 작업 중 하나라도 실패할 경우 전체를 롤백하여 데이터 일관성을 유지합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDataCleanupManager {

    private final UserRepositoryPort userRepositoryPort;
    private final RunningArtRepositoryPort runningArtRepositoryPort;
    private final RefreshTokenSessionPort refreshTokenSessionPort;

    /**
     * @param userId 삭제 대상 사용자의 식별자
     * @responsibility 1. 사용자가 생성한 러닝 아트 데이터를 삭제합니다.<br>
     * 2. 사용자의 핵심 프로필 데이터를 삭제합니다.<br>
     * 3. Redis에 저장된 리프레시 토큰(세션)을 제거합니다.
     */
    @Transactional
    public void deleteAllUserData(Long userId) {
        log.info("[DATA CLEANUP] 유저 데이터 파기 프로세스를 시작합니다. UserID: {}", userId);
        try {
            // 1. 연관 비즈니스 데이터 삭제 (RunningArt)
            runningArtRepositoryPort.deleteByUserId(userId);

            // 2. 핵심 유저 엔티티 삭제 (User)
            userRepositoryPort.deleteById(userId);

            // 3. 트랜잭션 커밋 후 Redis 데이터 삭제
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refreshTokenSessionPort.deleteAllRefreshTokens(userId);
                    log.info("[DATA CLEANUP] Redis 세션 데이터가 성공적으로 삭제되었습니다. UserID: {}", userId);
                }
            });
            log.info("[DATA CLEANUP] 유저의 DB 데이터가 성공적으로 파기 대기 상태입니다. UserID: {}", userId);
        } catch (Exception e) {
            log.error("[DATA CLEANUP] 유저 데이터 파기 중 오류가 발생했습니다. UserID: {}", userId, e);
            throw e; // 트랜잭션 롤백을 위해 예외를 재발생시킵니다.
        }
    }
}