package com.serverbe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 * 실제 MySQL과 Redis에 접속해 블랙리스트 조회 성능을 비교하는 테스트.
 * 기본 `gradlew test` 에서는 제외되며 `gradlew integrationTest` 로만 실행됩니다.
 */
@Tag("integration")
@SpringBootTest
class BlacklistPerformanceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final int TASK_COUNT = 1000; // 총 요청 수
    private static final int THREAD_COUNT = 100; // 동시 실행 스레드 수
    private String[] dummyTokens;

    @BeforeEach
    void setUp() {
        // 1. MySQL 테스트용 테이블 생성 및 초기화
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS refresh_token_blacklist (token VARCHAR(255) PRIMARY KEY)");
        jdbcTemplate.execute("TRUNCATE TABLE refresh_token_blacklist");

        // 2. Redis 테스트 데이터 초기화 (성능 테스트에 영향을 주지 않도록 flush DB 권장, 여기선 생략)
        
        // 3. 더미 토큰 1000개 미리 생성
        dummyTokens = new String[TASK_COUNT];
        for (int i = 0; i < TASK_COUNT; i++) {
            dummyTokens[i] = UUID.randomUUID().toString();
        }
    }

    @Test
    @DisplayName("1. [쓰기 성능] RDBMS vs Redis 블랙리스트 저장 속도 비교")
    void testWritePerformance() throws InterruptedException {
        System.out.println("========== [쓰기(Insert) 성능 비교] ==========");

        // [RDBMS 저장 테스트]
        long rdbmsTime = measureTime(() -> {
            jdbcTemplate.update("INSERT INTO refresh_token_blacklist (token) VALUES (?)", dummyTokens[TaskIndex.getAndIncrement()]);
        });
        System.out.println("RDBMS (MySQL) 저장 시간: " + rdbmsTime + " ms");

        TaskIndex.reset(); // 인덱스 초기화

        // [Redis 저장 테스트]
        long redisTime = measureTime(() -> {
            String token = dummyTokens[TaskIndex.getAndIncrement()];
            // Redis는 TTL(유효기간)을 7일로 설정하여 저장하는 실무 로직 반영
            redisTemplate.opsForValue().set("blacklist:" + token, "true", Duration.ofDays(7));
        });
        System.out.println("Redis 저장 시간: " + redisTime + " ms");
        System.out.println("===========================================\n");
    }

    @Test
    @DisplayName("2. [읽기 성능] RDBMS vs Redis 블랙리스트 검증 속도 비교")
    void testReadPerformance() throws InterruptedException {
        // 읽기 테스트를 위해 데이터를 먼저 양쪽에 적재합니다.
        for (String token : dummyTokens) {
            jdbcTemplate.update("INSERT INTO refresh_token_blacklist (token) VALUES (?)", token);
            redisTemplate.opsForValue().set("blacklist:" + token, "true", Duration.ofDays(7));
        }

        System.out.println("========== [읽기(Select) 성능 비교] ==========");

        // [RDBMS 검증 테스트]
        long rdbmsTime = measureTime(() -> {
            String token = dummyTokens[TaskIndex.getAndIncrement()];
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refresh_token_blacklist WHERE token = ?", Integer.class, token);
        });
        System.out.println("RDBMS (MySQL) 조회 시간: " + rdbmsTime + " ms");

        TaskIndex.reset();

        // [Redis 검증 테스트]
        long redisTime = measureTime(() -> {
            String token = dummyTokens[TaskIndex.getAndIncrement()];
            redisTemplate.hasKey("blacklist:" + token);
        });
        System.out.println("Redis 조회 시간: " + redisTime + " ms");
        System.out.println("===========================================");
    }

    // --- 동시성 테스트를 위한 유틸리티 메서드 및 클래스 ---

    private long measureTime(Runnable logic) throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(TASK_COUNT);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < TASK_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    logic.run();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(); // 모든 스레드의 작업이 끝날 때까지 대기
        long endTime = System.currentTimeMillis();
        executorService.shutdown();

        return endTime - startTime;
    }

    // 람다식 내부에서 인덱스를 증가시키기 위한 간단한 유틸
    private static class TaskIndex {
        private static int index = 0;
        public static synchronized int getAndIncrement() {
            return index++;
        }
        public static synchronized void reset() {
            index = 0;
        }
    }
}