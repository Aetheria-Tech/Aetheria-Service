package com.serverbe.infrastructure.config.event;

import com.github.benmanes.caffeine.cache.Cache;
import com.serverbe.application.port.out.notification.AlertNotificationPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class) // 스프링 없이 가볍게 실행!
class CircuitBreakerEventListenerTest {

    @Mock
    private AlertNotificationPort alertNotificationPort;

    private CircuitBreakerEventListener eventListener;
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Mock
    private Cache<String, Integer> localRateLimitCache;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        eventListener = new CircuitBreakerEventListener(circuitBreakerRegistry, localRateLimitCache, alertNotificationPort);
        eventListener.registerEventListener();
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisRateLimit");
    }

    @Test
    @DisplayName("서킷 브레이커가 CLOSED(복구) 상태로 전환되면 캐시를 초기화하고 복구 알림을 보낸다")
    void shouldInvalidateCacheAndSendNotification_whenStateTransitionsToClosed() {
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToClosedState();

        verify(localRateLimitCache, times(1)).invalidateAll();
        verify(alertNotificationPort, times(1)).sendAlert(contains("복구"));
    }

    @Test
    @DisplayName("서킷 브레이커가 OPEN(장애) 상태로 전환될 때는 캐시를 유지하고 장애 알림을 보낸다")
    void shouldNotInvalidateCacheAndSendNotification_whenStateTransitionsToOpen() {
        circuitBreaker.transitionToOpenState();

        verify(localRateLimitCache, never()).invalidateAll();
        verify(alertNotificationPort, times(1)).sendAlert(contains("장애"));
    }

    @Test
    @DisplayName("서킷 브레이커가 HALF_OPEN(반오픈) 상태로 전환될 때는 아무 일도 하지 않는다")
    void shouldDoNothing_whenStateTransitionsToHalfOpen() {
        circuitBreaker.transitionToOpenState();
        clearInvocations(alertNotificationPort);

        circuitBreaker.transitionToHalfOpenState();

        verify(localRateLimitCache, never()).invalidateAll();
        verify(alertNotificationPort, never()).sendAlert(anyString());
    }
}