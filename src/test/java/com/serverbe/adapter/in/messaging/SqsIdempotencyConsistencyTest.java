package com.serverbe.adapter.in.messaging;

import com.serverbe.adapter.in.messaging.dto.SageMakerNotificationDto;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 이력서의 <b>"분산 환경 데이터 정합성 100% 확보"</b>를 실제 큐로 재현해 확인하는 측정 테스트입니다.
 * <p>
 * 이 테스트가 생기기 전까지 멱등성 검증은 {@code findByIdForUpdate} 를 Mockito 로 스텁한 단위
 * 테스트뿐이었습니다. 스텁은 우리가 짠 분기가 의도대로 갈라지는지만 보여 줄 뿐,
 * <b>{@code SELECT ... FOR UPDATE} 가 정말 직렬화하는지</b>도,
 * <b>SQS 의 at-least-once 중복 전달이 정말 걸러지는지</b>도 증명하지 못합니다.
 * 두 질문 모두 실제 MySQL 의 행 락과 실제 큐의 재전달 위에서만 답이 나옵니다.
 * </p>
 *
 * @implSpec 컨테이너 셋을 직접 띄웁니다 — MySQL 8.0(비관적 락), LocalStack SQS(at-least-once 재전달),
 * Redis(컨텍스트 기동에 필요). 로컬에 무엇이 깔려 있든 결과가 같아야 재현 가능한 측정입니다.
 * @implNote 큐의 가시성 타임아웃을 5초로 짧게 잡았습니다. 운영 기본값(30초)이면 PENDING 재시도
 * 시나리오 하나에 30초를 기다려야 하고, 짧게 잡을수록 중복 전달이 더 자주 일어나 <b>측정이 더
 * 불리해집니다.</b> 유리한 쪽으로 조건을 고르지 않기 위한 선택입니다.
 * @implNote 측정 조건과 결과 해석은 {@code docs/benchmark/02-sqs-idempotency-consistency.md} 에 있습니다.
 */
@Tag("integration")
@Testcontainers
@ActiveProfiles("local")
@SpringBootTest
@DisplayName("[측정] SQS 멱등성 - 중복 전달 하에서의 데이터 정합성")
class SqsIdempotencyConsistencyTest {

    /** 알림 큐 이름. 리스너가 {@code aws.sqs.ai-notification-queue-name} 으로 찾아간다. */
    private static final String QUEUE_NAME = "ai-notification-queue-test";

    /** 서로 다른 작업 수. 한 건만으로는 "우연히 직렬화됐다"를 배제하지 못한다. */
    private static final int TASK_COUNT = 20;

    /** 작업 하나당 중복 전달 횟수. SQS 가 같은 알림을 몇 번 다시 주는 상황을 흉내 낸다. */
    private static final int DUPLICATES_PER_TASK = 5;

    /** 정합성이 깨지면 늦게라도 깨진다. 결과가 흔들리지 않고 유지되는지 이 시간만큼 더 지켜본다. */
    private static final Duration STABILITY_WINDOW = Duration.ofSeconds(10);

    // 운영 RDS 가 8.0 이다(infra/lib/data-stack.ts). 태그를 8 로 두면 8.4 가 끌려와
    // Flyway 가 "지원 검증되지 않은 버전" 경고를 낸다.
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("webflux");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.4"))
                    .withServices(LocalStackContainer.Service.SQS);

    /**
     * 컨테이너 접속 정보를 컨텍스트에 주입하고, <b>컨텍스트가 뜨기 전에</b> 큐를 만들어 둡니다.
     * <p>
     * 순서가 중요합니다. {@code @SqsListener} 는 기동 중 {@code GetQueueUrl} 로 큐를 찾으므로,
     * 큐가 없으면 컨텍스트 기동 자체가 실패합니다. {@code @DynamicPropertySource} 는 컨테이너가
     * 올라온 뒤·컨텍스트가 만들어지기 전에 호출되는 지점이라 여기서 큐를 만듭니다.
     * </p>
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        createQueue();

        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // gradlew test / integrationTest 는 AWS_SQS_ENABLED=false 를 걸어 둔다(자격증명 없는 CI 대비).
        // 이 테스트는 폴링이 목적이므로 그 위에 덮어쓴다.
        registry.add("spring.cloud.aws.sqs.enabled", () -> true);
        registry.add("spring.cloud.aws.sqs.endpoint",
                () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS).toString());
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);

        registry.add("aws.sqs.ai-notification-queue-name", () -> QUEUE_NAME);
    }

    private static void createQueue() {
        try {
            // 가시성 타임아웃을 짧게. 짧을수록 중복 전달이 늘어 측정이 불리해진다.
            var result = LOCALSTACK.execInContainer(
                    "awslocal", "sqs", "create-queue",
                    "--queue-name", QUEUE_NAME,
                    "--attributes", "VisibilityTimeout=5");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("큐 생성 실패: " + result.getStderr());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("큐 생성 중 인터럽트", e);
        } catch (Exception e) {
            throw new IllegalStateException("큐 생성 중 오류", e);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqsTemplate sqsTemplate;

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Test
    @DisplayName("같은 알림이 5번씩 중복 전달돼도 러닝아트는 작업당 정확히 1건만 생성된다")
    void duplicateDeliveriesProduceExactlyOneArtPerTask() {
        long userId = insertUser("dup");
        List<String> taskIds = insertTasks(userId, TASK_COUNT, "PROCESSING");

        int delivered = sendNotifications(taskIds, DUPLICATES_PER_TASK);

        // 1) 모든 작업이 종결될 때까지 기다린다.
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> countTasks(userId, "COMPLETED") == TASK_COUNT);

        // 2) 큐가 완전히 비워질 때까지 기다린다.
        //    이 조건이 없으면 "나머지가 아직 도착하지 않았을 뿐인데 정합성이 맞아 보이는" 착시가 생긴다.
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> remainingMessages() == 0);

        // 3) 결과가 흔들리지 않는지 더 지켜본다. 뒤늦은 중복은 늦게 도착한다.
        await().atMost(STABILITY_WINDOW.plusSeconds(5))
                .during(STABILITY_WINDOW)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> countArts(userId) == TASK_COUNT);

        long arts = countArts(userId);
        printSummary("중복 전달", delivered, TASK_COUNT, arts);

        assertThat(arts)
                .as("전달 %d건 중 실제 등록은 작업 수와 같아야 한다", delivered)
                .isEqualTo(TASK_COUNT);
        assertThat(countDistinctResultArtIds(userId))
                .as("작업마다 서로 다른 러닝아트 1건씩에 연결되어야 한다")
                .isEqualTo(TASK_COUNT);
        assertThat(countTasks(userId, "FAILED"))
                .as("중복은 실패가 아니라 멱등 스킵으로 처리되어야 한다")
                .isZero();
    }

    @Test
    @DisplayName("PENDING 상태에서 먼저 도착한 콜백은 유실되지 않고 재시도돼 정확히 1번 처리된다")
    void callbackArrivingBeforeProcessingIsRetriedNotLost() {
        long userId = insertUser("race");
        String taskId = insertTasks(userId, 1, "PENDING").get(0);

        sendNotifications(List.of(taskId), 1);

        // 요청 스레드가 아직 PROCESSING 을 저장하지 못한 구간. 리스너는 예외를 던지고 큐는 재전달한다.
        // 이 구간 동안 결과가 만들어지면 안 된다 - 실패로 확정해 버리는 것도, 미리 등록해 버리는 것도 오답이다.
        await().atMost(Duration.ofSeconds(12))
                .during(Duration.ofSeconds(8))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> countArts(userId) == 0 && countTasks(userId, "FAILED") == 0);

        // 요청 스레드가 뒤늦게 PROCESSING 저장을 마친 상황.
        jdbcTemplate.update(
                "UPDATE ai_generation_tasks SET status = 'PROCESSING', updated_at = NOW(6) WHERE id = ?", taskId);

        await().atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> countTasks(userId, "COMPLETED") == 1);

        await().atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> remainingMessages() == 0);

        await().atMost(STABILITY_WINDOW.plusSeconds(5))
                .during(STABILITY_WINDOW)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> countArts(userId) == 1);

        printSummary("PENDING 선착 콜백", 1, 1, countArts(userId));

        assertThat(countArts(userId))
                .as("재시도가 여러 번 일어나도 등록은 한 번뿐이어야 한다")
                .isEqualTo(1L);
    }

    // ---------------------------------------------------------------- 준비 및 관측

    /**
     * 테스트마다 다른 사용자를 만듭니다. 러닝아트 개수를 {@code user_id} 로 세기 때문에,
     * 사용자를 공유하면 앞선 테스트의 뒤늦은 중복이 다음 테스트의 숫자를 오염시킵니다.
     */
    private long insertUser(String tag) {
        String oauthId = tag + "-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (oauth_id, oauth_provider, email, nickname, role, created_at, updated_at) "
                        + "VALUES (?, 'KAKAO', ?, ?, 'USER', NOW(6), NOW(6))",
                oauthId, oauthId + "@example.com", tag);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE oauth_id = ?", Long.class, oauthId);
    }

    private List<String> insertTasks(long userId, int count, String status) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String taskId = UUID.randomUUID().toString();
            jdbcTemplate.update(
                    "INSERT INTO ai_generation_tasks "
                            + "(id, user_id, shape, proficiency, status, input_s3_uri, output_s3_uri, created_at, updated_at) "
                            + "VALUES (?, ?, 'HEART', 'BEGINNER', ?, ?, ?, NOW(6), NOW(6))",
                    taskId, userId, status,
                    "s3://bench-input/inputs/" + taskId + ".json",
                    outputUri(taskId));
            ids.add(taskId);
        }
        return ids;
    }

    /**
     * 같은 알림을 {@code copies} 번씩 보냅니다. 표준 큐는 같은 본문이라도 별개 메시지로 취급하므로,
     * 이것이 곧 at-least-once 중복 전달입니다.
     *
     * @implNote 작업 순서대로가 아니라 <b>섞어서</b> 보냅니다. 작업별로 몰아 보내면 같은 행을 노리는
     * 사본들이 시간상 떨어져 도착해, 정작 검증하려던 행 락 경합이 일어나지 않습니다.
     */
    private int sendNotifications(List<String> taskIds, int copies) {
        List<String> plan = new ArrayList<>(taskIds.size() * copies);
        for (int i = 0; i < copies; i++) {
            plan.addAll(taskIds);
        }
        Collections.shuffle(plan);

        plan.parallelStream().forEach(taskId -> sqsTemplate.send(QUEUE_NAME, new SageMakerNotificationDto(
                "Completed",
                taskId,
                null,
                new SageMakerNotificationDto.ResponseParameters("application/json", outputUri(taskId))
        )));

        return plan.size();
    }

    private String outputUri(String taskId) {
        // SageMaker 가 입력 파일명 뒤에 .out 을 덧붙이는 실제 형식을 그대로 쓴다.
        return "s3://bench-output/outputs/" + taskId + ".json.out";
    }

    private long countTasks(long userId, String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_generation_tasks WHERE user_id = ? AND status = ?",
                Long.class, userId, status);
    }

    private long countArts(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM running_arts WHERE user_id = ?", Long.class, userId);
    }

    private long countDistinctResultArtIds(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT result_art_id) FROM ai_generation_tasks "
                        + "WHERE user_id = ? AND result_art_id IS NOT NULL",
                Long.class, userId);
    }

    /** 큐에 남아 있는 메시지 수(대기 + 처리 중). 0 이어야 "전량 소비"라고 말할 수 있다. */
    private long remainingMessages() {
        String queueUrl = sqsAsyncClient.getQueueUrl(
                GetQueueUrlRequest.builder().queueName(QUEUE_NAME).build()).join().queueUrl();

        Map<QueueAttributeName, String> attributes = sqsAsyncClient.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
                        .build()).join().attributes();

        return Long.parseLong(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                + Long.parseLong(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }

    /** 문서에 그대로 옮길 수 있는 형태로 결과를 남깁니다. */
    private void printSummary(String scenario, long delivered, long expected, long actual) {
        double consistency = expected == 0 ? 0 : 100.0 * (1 - Math.abs(actual - expected) / (double) expected);
        System.out.printf(
                "[SQS 멱등성 측정] 시나리오=%s | 전달=%d건 | 기대 등록=%d건 | 실제 등록=%d건 | 정합성=%.2f%%%n",
                scenario, delivered, expected, actual, consistency);
    }
}
