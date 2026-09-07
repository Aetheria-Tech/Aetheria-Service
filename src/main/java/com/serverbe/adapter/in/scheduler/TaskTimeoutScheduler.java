package com.serverbe.adapter.in.scheduler;

import com.serverbe.application.port.in.task.CleanupZombieTaskUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 작업 파이프라인의 타임아웃을 관리하고 좀비 태스크를 주기적으로 정리하는 <b>인바운드 어댑터</b>.
 * <p>
 * <b>설계 원칙:</b><br>
 * 시간이 트리거하는 진입점일 뿐 비즈니스 로직을 직접 수행하지 않습니다. 응용 계층의 인바운드 포트인
 * {@link CleanupZombieTaskUseCase}를 호출해 제어 흐름을 도메인 로직으로 위임합니다.
 * </p>
 * <p>
 * HTTP 요청 대신 시각이 흐름을 시작시킬 뿐, 컨트롤러와 방향이 같습니다. 그래서 인프라가 아니라
 * {@code adapter.in}에 둡니다.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTimeoutScheduler {

    /** * 좀비 작업 정리 비즈니스 로직을 담당하는 인바운드 포트.
     * 인프라 계층이 도메인 계층의 상세 구현(JPA 등)에 직접 의존하지 않도록 인터페이스를 통해 협력합니다.
     */
    private final CleanupZombieTaskUseCase cleanupZombieTaskUseCase;

    /**
     * 5분 간격으로 좀비 작업 정리 로직을 실행합니다.
     * <p>
     * <b>스케줄링 상세:</b><br>
     * - Cron 표현식: {@code 0 0/5 * * * *} (매 5분마다 0초에 실행)<br>
     * - 대상: 실행 중(PROCESSING) 또는 대기 중(PENDING) 상태로 일정 시간(예: 10분) 이상 방치된 작업들<br>
     * - 목적: 시스템 자원 낭비 방지 및 클라이언트에게 실시간 실패 알림 보장
     * </p>
     */
    @Scheduled(cron = "0 0/5 * * * *")
    @SchedulerLock(
            name = "scheduleCleanUp_lock", // Redis에 저장될 락의 고유 이름
            lockAtLeastFor = "4m",         // 락을 최소한 유지할 시간 (중복 실행 방지의 핵심 방어선)
            lockAtMostFor = "10m"           // 락을 최대한 유지할 시간 (서버 다운 시 데드락 방지용)
    )
    /*
     * 💡 [ShedLock 속성 완벽 가이드]
     *
     * 1. lockAtLeastFor = "4m" (최소 잠금 시간)
     * - 작업이 1초 만에 끝나더라도, 락(Lock)을 4분 동안은 강제로 유지합니다.
     * - 이유: A 서버와 B 서버의 시스템 시계(NTP)가 미세하게 달라서 1~2초 차이로 스케줄러가 트리거될 때,
     * A가 작업을 순식간에 끝내고 락을 풀어버리면 B가 "어? 락 없네?" 하고 또 실행하는 것을 막아줍니다.
     * - 설정 팁: 스케줄러 실행 주기(5분)보다 약간 짧게 설정하는 것이 베스트입니다.
     *
     * 2. lockAtMostFor = "5m" (최대 잠금 시간)
     * - A 서버가 락을 잡고 작업을 하다가 갑자기 뻗어버렸을(OOM, 강제종료 등) 때,
     * 이 락이 영원히 풀리지 않는 데드락(Deadlock) 상태를 방지하는 안전장치입니다.
     * - 시간이 지나면 Redis에서 자동으로 락이 소멸됩니다.
     * - 설정 팁: 로직이 최악의 경우에도 이 시간 안에는 끝날 것이라고 예상되는 시간보다 길게 설정해야 합니다.
     * (만약 로직이 6분 걸리는데 lockAtMostFor가 5분이면, 5분 뒤 락이 풀려 B 서버가 중복 실행해버립니다.)
     */
    public void scheduleCleanUp() {
        log.info("[Task Scheduler] 좀비 작업 정리 프로세스 가동 (5분 주기)");

        // 실제 데이터 필터링 및 상태 변경 로직은 UseCase 구현체(Service) 내부에 격리되어 있음
        cleanupZombieTaskUseCase.cleanUpZombieTasks();

        log.info("[Task Scheduler] 좀비 작업 정리 프로세스 완료");
    }
}