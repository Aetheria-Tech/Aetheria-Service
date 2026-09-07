package com.serverbe;

import com.serverbe.application.service.RateLimiterService;
import com.serverbe.infrastructure.config.properties.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 실제 Redis에 접속해 Lua 토큰 버킷의 동시성 정확도를 검증하는 테스트.
 * 기본 `gradlew test` 에서는 제외되며 `gradlew integrationTest` 로만 실행됩니다.
 */
@Tag("integration")
@SpringBootTest
class RateLimiterServiceConcurrencyTest {

    @Autowired
    private RateLimiterService rateLimiterService;

    // 설정값을 동적으로 가져오기 위해 Properties도 주입받습니다.
    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final int THREAD_COUNT = 100; // 동시에 쏟아질 트래픽 수
    private static final Long TEST_USER_ID = 1004L; // 파라미터 타입에 맞춘 Long ID
    private static final String TEST_ENDPOINT = "/api/test/concurrency"; // 테스트용 가짜 엔드포인트

    @BeforeEach
    void setUp() {
        // 🌟 수정됨: 새롭게 바뀐 Key 조합 규칙(prefix + id + ":" + endpoint) 적용
        String key = rateLimitProperties.prefix().user() + TEST_USER_ID + ":" + TEST_ENDPOINT;
        redisTemplate.delete(key);
    }

    @Test
    @DisplayName("토큰 버킷 동시성 테스트: 100개의 스레드가 동시 요청해도 정확히 Capacity 설정값만큼만 통과해야 한다.")
    void isAllowedForUser_Concurrency_Test() throws InterruptedException {
        // given: application.yml에 설정된 최대 버킷 용량과 보충 속도를 동적으로 가져옴
        final int capacity = rateLimitProperties.user().capacity();
        final int refillRate = rateLimitProperties.user().refillRate();

        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when: 100개의 스레드를 출발선에 대기시킴
        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();

                    // 🌟 수정됨: 엔드포인트, capacity, refillRate 파라미터 추가 전달
                    boolean isAllowed = rateLimiterService.isAllowedForUser(
                            TEST_USER_ID, TEST_ENDPOINT, capacity, refillRate
                    );

                    if (isAllowed) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(); // 모든 스레드 준비 완료 대기

        long startTime = System.currentTimeMillis();

        startLatch.countDown(); // 🚀 신호총 발사! (100개 스레드 동시 돌진)

        doneLatch.await(); // 모든 스레드 작업 완료 대기

        long endTime = System.currentTimeMillis();

        // then: 결과 출력 및 검증
        System.out.println("\n========== [Rate Limit 동시성 테스트 결과] ==========");
        System.out.println("테스트 엔드포인트: " + TEST_ENDPOINT);
        System.out.println("설정된 최대 허용량 (Capacity): " + capacity);
        System.out.println("총 소요 시간: " + (endTime - startTime) + " ms");
        System.out.println("통과된 요청 (Success): " + successCount.get() + " 개");
        System.out.println("차단된 요청 (Fail): " + failCount.get() + " 개");
        System.out.println("====================================================\n");

        // 검증: 성공한 요청은 정확히 버킷 용량(Capacity)과 일치해야 하고, 나머지는 모두 실패해야 함
        assertThat(successCount.get()).isEqualTo(capacity);
        assertThat(failCount.get()).isEqualTo(THREAD_COUNT - capacity);
    }
}