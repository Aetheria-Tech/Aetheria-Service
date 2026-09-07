package com.serverbe.adapter.out.external.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.application.port.out.dto.ai.AiPromptCommand;
import com.serverbe.infrastructure.config.properties.AwsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * @responsibility 프롬프트가 S3에 어떤 형태로 올라가는지를 고정합니다.
 * @implSpec <b>이 테스트가 지키는 것은 SageMaker 추론 스크립트와의 계약입니다.</b>
 * {@link AiPromptCommand}의 레코드 컴포넌트 이름이 곧 JSON 키이므로, 이름을 바꾸면 추론이 값을
 * 찾지 못한 채 조용히 기본값으로 동작합니다. 그런 실패는 로그에도 남지 않습니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 입력 S3 어댑터")
class S3AiInputAdapterTest {

    @Mock
    private S3Client s3Client;

    private S3AiInputAdapter adapter;

    private static final String BUCKET = "aetheria-ai-requests-test";
    private static final String TASK_ID = "task-1234";

    @BeforeEach
    void setUp() {
        AwsProperties properties = new AwsProperties(
                new AwsProperties.S3(BUCKET, BUCKET, null), null, null);
        adapter = new S3AiInputAdapter(s3Client, new ObjectMapper(), properties);
    }

    @Test
    @DisplayName("프롬프트를 네 개 키의 JSON으로 직렬화해 inputs/{taskId}.json 으로 올린다")
    void 프롬프트를_네_개_키의_JSON으로_올린다() throws IOException {
        AiPromptCommand prompt = new AiPromptCommand(37.5, 127.0, "HEART", "BEGINNER");

        String s3Uri = adapter.uploadInputJson(TASK_ID, prompt);

        assertThat(s3Uri).isEqualTo("s3://" + BUCKET + "/inputs/" + TASK_ID + ".json");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(request.capture(), body.capture());

        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo("inputs/" + TASK_ID + ".json");
        assertThat(request.getValue().contentType()).isEqualTo("application/json");

        Map<String, Object> uploaded = readJson(body.getValue());
        assertThat(uploaded)
                .containsOnlyKeys("latitude", "longitude", "shape", "proficiency")
                .containsEntry("latitude", 37.5)
                .containsEntry("longitude", 127.0)
                .containsEntry("shape", "HEART")
                .containsEntry("proficiency", "BEGINNER");
    }

    @Test
    @DisplayName("기본값으로 채워진 프롬프트도 같은 키 집합을 유지한다")
    void 기본값으로_채워진_프롬프트도_같은_키_집합을_유지한다() throws IOException {
        adapter.uploadInputJson(TASK_ID, new AiPromptCommand(37.5, 127.0, "", "BEGINNER"));

        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(any(PutObjectRequest.class), body.capture());

        assertThat(readJson(body.getValue()))
                .containsOnlyKeys("latitude", "longitude", "shape", "proficiency")
                .containsEntry("shape", "");
    }

    private Map<String, Object> readJson(RequestBody body) throws IOException {
        try (InputStream in = body.contentStreamProvider().newStream()) {
            return new ObjectMapper().readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
        }
    }
}
