package com.serverbe.adapter.out.external.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serverbe.application.port.in.dto.art.AiGenerationResultDto;
import com.serverbe.domain.exception.s3.S3ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * @responsibility S3 URI가 버킷·키로 갈라지는 규칙과 <b>예외 다섯 갈래의 번역</b>을 고정합니다.
 * @implSpec 가장 중요한 것은 <b>{@code NoSuchKeyException}이 예외가 아니라는 것</b>입니다.
 * 결과물이 아직 없다는 것은 "AI 작업이 진행 중"이라는 정상 신호이고, 이것이 예외로 바뀌면
 * <b>폴링 요청이 전부 500으로 떨어집니다.</b> 나머지 넷은 전부 {@code S3_DOWNLOAD_ERROR}로 모입니다.
 * @implNote 본보기는 {@code S3AiInputAdapterTest}입니다 — {@code S3Client}를 목으로 두고 요청
 * 객체의 조립을 {@code ArgumentCaptor}로 봅니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 결과물 S3 어댑터")
class S3AiOutputAdapterTest {

    @Mock
    private S3Client s3Client;

    private S3AiOutputAdapter adapter;

    private static final String BUCKET = "aetheria-ai-output-test";
    private static final String KEY = "outputs/task-1234.json";
    private static final String S3_URI = "s3://" + BUCKET + "/" + KEY;

    @BeforeEach
    void setUp() {
        adapter = new S3AiOutputAdapter(s3Client, new ObjectMapper());
    }

    private static ResponseInputStream<GetObjectResponse> stream(String json) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(
                        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
    }

    private static software.amazon.awssdk.services.s3.model.S3Exception s3ServerError() {
        return (software.amazon.awssdk.services.s3.model.S3Exception)
                software.amazon.awssdk.services.s3.model.S3Exception.builder()
                        .statusCode(403)
                        .awsErrorDetails(AwsErrorDetails.builder().errorMessage("Access Denied").build())
                        .build();
    }

    @Nested
    @DisplayName("다운로드")
    class 다운로드 {

        @Test
        @DisplayName("s3:// URI를 버킷과 키로 갈라 요청한다")
        void URI를_버킷과_키로_가른다() {
            given(s3Client.getObject(any(GetObjectRequest.class)))
                    .willReturn(stream("""
                            {"startLat":37.5,"startLon":127.0,"distance":5.2,"gpx":"encoded"}"""));

            Optional<AiGenerationResultDto> result = adapter.downloadOutput(S3_URI);

            assertThat(result).isPresent();
            assertThat(result.get().startLat()).isEqualTo(37.5);
            assertThat(result.get().distance()).isEqualTo(5.2);
            assertThat(result.get().gpx()).isEqualTo("encoded");

            ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(s3Client).getObject(request.capture());
            assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
            assertThat(request.getValue().key()).isEqualTo(KEY);
        }

        /**
         * @implNote <b>정상적인 비즈니스 흐름입니다.</b> 파일이 없다는 것은 실패가 아니라 "아직
         * 추론이 안 끝났다"는 뜻이고, 폴링하는 클라이언트가 계속 기다리게 하는 신호입니다.
         */
        @Test
        @DisplayName("파일이 아직 없으면 예외가 아니라 빈 결과다")
        void 파일이_없으면_빈_결과다() {
            given(s3Client.getObject(any(GetObjectRequest.class)))
                    .willThrow(NoSuchKeyException.builder().message("no key").build());

            assertThat(adapter.downloadOutput(S3_URI)).isEmpty();
        }

        @Test
        @DisplayName("S3 서버 거절은 다운로드 오류로 번역된다")
        void S3_서버_거절은_다운로드_오류가_된다() {
            given(s3Client.getObject(any(GetObjectRequest.class))).willThrow(s3ServerError());

            assertThatThrownBy(() -> adapter.downloadOutput(S3_URI))
                    .isInstanceOf(com.serverbe.domain.exception.s3.S3Exception.class)
                    .hasFieldOrPropertyWithValue("errorCode", S3ErrorCode.S3_DOWNLOAD_ERROR);
        }

        @Test
        @DisplayName("네트워크 실패도 같은 다운로드 오류로 모인다")
        void 네트워크_실패도_같은_오류로_모인다() {
            given(s3Client.getObject(any(GetObjectRequest.class)))
                    .willThrow(SdkClientException.create("connection reset"));

            assertThatThrownBy(() -> adapter.downloadOutput(S3_URI))
                    .isInstanceOf(com.serverbe.domain.exception.s3.S3Exception.class)
                    .hasFieldOrPropertyWithValue("errorCode", S3ErrorCode.S3_DOWNLOAD_ERROR);
        }

        /**
         * @implNote AI가 규격에 맞지 않는 JSON을 내려놓는 경우입니다. 이것이 통과되면 필드가 전부
         * {@code null}인 DTO가 DB까지 흘러갑니다.
         */
        @Test
        @DisplayName("깨진 JSON도 다운로드 오류로 막는다")
        void 깨진_JSON도_막는다() {
            given(s3Client.getObject(any(GetObjectRequest.class))).willReturn(stream("{not json"));

            assertThatThrownBy(() -> adapter.downloadOutput(S3_URI))
                    .isInstanceOf(com.serverbe.domain.exception.s3.S3Exception.class)
                    .hasFieldOrPropertyWithValue("errorCode", S3ErrorCode.S3_DOWNLOAD_ERROR);
        }

        @Test
        @DisplayName("s3:// 형식이 아닌 URI는 S3를 부르기 전에 막는다")
        void 형식이_아닌_URI는_부르기_전에_막는다() {
            assertThatThrownBy(() -> adapter.downloadOutput("https://example.com/a.json"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> adapter.downloadOutput(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("삭제 — 장애를 격리한다")
    class 삭제 {

        @Test
        @DisplayName("버킷과 키로 삭제를 요청한다")
        void 버킷과_키로_삭제를_요청한다() {
            adapter.deleteOutput(S3_URI);

            ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3Client).deleteObject(request.capture());
            assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
            assertThat(request.getValue().key()).isEqualTo(KEY);
        }

        /**
         * @implNote <b>장애 격리(Fault Isolation)입니다.</b> 삭제는 비용 최적화일 뿐이고, 이 시점에
         * 데이터는 이미 DB에 저장되어 있습니다. 여기서 예외를 던지면 <b>후속 알림 파이프라인이
         * 끊깁니다</b> — 사용자는 결과를 못 받는데 데이터는 멀쩡히 있는 상태가 됩니다.
         * 남은 찌꺼기 파일은 S3 Lifecycle 정책이 치웁니다.
         */
        @Test
        @DisplayName("어떤 실패에도 예외를 밖으로 던지지 않는다")
        void 어떤_실패에도_예외를_던지지_않는다() {
            willThrow(s3ServerError()).given(s3Client).deleteObject(any(DeleteObjectRequest.class));
            assertThatCode(() -> adapter.deleteOutput(S3_URI)).doesNotThrowAnyException();

            willThrow(SdkClientException.create("timeout"))
                    .given(s3Client).deleteObject(any(DeleteObjectRequest.class));
            assertThatCode(() -> adapter.deleteOutput(S3_URI)).doesNotThrowAnyException();

            willThrow(new IllegalStateException("알 수 없음"))
                    .given(s3Client).deleteObject(any(DeleteObjectRequest.class));
            assertThatCode(() -> adapter.deleteOutput(S3_URI)).doesNotThrowAnyException();
        }
    }
}
