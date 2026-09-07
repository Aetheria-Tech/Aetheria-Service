package com.serverbe.infrastructure.config.event;

import com.github.benmanes.caffeine.cache.Cache;
import com.serverbe.application.port.out.notification.AlertNotificationPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resilience4j 서킷 브레이커(Circuit Breaker)의 상태 변화(OPEN/CLOSED)를 감지하고 후속 처리를 담당하는 글로벌 이벤트 리스너.
 * <p>
 * <b>주요 역할 (Responsibilities):</b><br>
 * 1. <b>실시간 장애 모니터링:</b> 외부 인프라(예: Redis, S3 등) 통신에 장애가 발생하여 서킷이 OPEN 되거나, 다시 복구되어 CLOSED 될 때 Discord로 즉각적인 알림을 발송합니다.<br>
 * 2. <b>L1 Fallback 제어 (장애 대응):</b> Redis 기반의 Rate Limit 장애 시 대체 투입되는 로컬 캐시(Caffeine)의 생명주기를 관리합니다. (Redis 복구 시 로컬 캐시 초기화 등)
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerEventListener {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Cache<String, Integer> localRateLimitCache;
    private final AlertNotificationPort alertNotificationPort;

    /**
     * 스프링 빈(Bean) 초기화 직후, 애플리케이션 내의 모든 서킷 브레이커에 상태 변화 감지 리스너를 등록합니다.
     * <p>
     * <b>구현 디테일:</b><br>
     * 시스템 시작 시점에 이미 생성된 서킷 브레이커뿐만 아니라,
     * 런타임에 지연 생성(Lazy Initialization)되는 서킷 브레이커에도 리스너가 누락 없이 부착되도록
     * {@code onEntryAdded} 이벤트를 함께 구독합니다.
     * </p>
     */
    @PostConstruct
    public void registerEventListener() {
        // 1. 현재 레지스트리에 등록된 모든 서킷 브레이커에 리스너 등록
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(this::addStateTransitionListener);

        // 2. (안전장치) 애플리케이션 실행 후 지연 생성(Lazy Init)되는 서킷 브레이커에도 리스너 등록
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(entryAddedEvent -> addStateTransitionListener(entryAddedEvent.getAddedEntry()));
    }

    /**
     * 개별 서킷 브레이커 객체에 상태 전이(State Transition) 이벤트를 수신하는 콜백을 부착합니다.
     *
     * @param circuitBreaker 리스너를 부착할 대상 서킷 브레이커 인스턴스
     */
    private void addStateTransitionListener(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> {
                    String cbName = circuitBreaker.getName();
                    CircuitBreaker.State toState = event.getStateTransition().getToState();

                    // 서킷 브레이커의 최종 변경 상태에 따라 분기 처리
                    if (toState == CircuitBreaker.State.CLOSED) {
                        handleClosedState(cbName);
                    } else if (toState == CircuitBreaker.State.OPEN) {
                        handleOpenState(cbName);
                    }
                });
    }

    /**
     * 서킷 브레이커가 정상 상태(CLOSED)로 복구되었을 때의 후속 작업을 처리합니다.
     *
     * @param cbName 복구된 서킷 브레이커의 이름
     */
    private void handleClosedState(String cbName) {
        log.info("[{}] 서킷 브레이커 복구 완료! (CLOSED)", cbName);

        // Redis Rate Limit 전용 로직: Redis가 정상화되었으므로, 장애 기간 동안 사용하던 임시 로컬 캐시(L1)를 초기화하여 데이터 정합성을 맞춥니다.
        if ("redisRateLimit".equals(cbName)) {
            log.info("[L1 Cache] Redis 복구 완료! 로컬 방어막(L1) 초기화.");
            localRateLimitCache.invalidateAll();
        }

        // 복구 완료 메시지를 관리자 디스코드 채널로 발송
        alertNotificationPort.sendAlert(
                String.format("🟢 **[복구 알림]** `%s` 연결이 복구되어 서킷 브레이커가 CLOSED 되었습니다. 시스템이 정상화되었습니다.", cbName)
        );
    }

    /**
     * 대상 시스템 장애로 인해 서킷 브레이커가 차단 상태(OPEN)로 전환되었을 때의 후속 작업을 처리합니다.
     *
     * @param cbName 장애가 발생한 서킷 브레이커의 이름
     */
    private void handleOpenState(String cbName) {
        log.error("🚨 [{}] 장애 발생! 서킷 브레이커가 OPEN 되었습니다!", cbName);

        // Redis Rate Limit 서킷이 열린 경우, 관리자가 L1 폴백(로컬 캐시)이 동작 중임을 인지할 수 있도록 메시지 보강
        String extraMessage = "redisRateLimit".equals(cbName) ? " L1 로컬 방어막이 가동됩니다. " : " ";

        // 장애 발생 메시지를 관리자 디스코드 채널로 즉시 발송
        alertNotificationPort.sendAlert(
                String.format("🔴 **[장애 알림]** `%s` 장애 발생으로 서킷 브레이커가 OPEN 되었습니다!%s확인이 필요합니다!", cbName, extraMessage)
        );
    }
}