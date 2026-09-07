package com.serverbe.adapter.out.notification;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @responsibility Discord 웹훅 어댑터가 보내는 요청의 형태를 고정합니다.
 * @implNote 전송이 Fire-and-Forget(비동기 {@code subscribe})이라 호출 직후에는 요청이 아직 나가지
 * 않았을 수 있습니다. {@code takeRequest(timeout)}으로 도착을 기다립니다.
 */
@DisplayName("Discord 알림 어댑터")
class DiscordAlertAdapterTest {

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private DiscordAlertAdapter adapterWithUrl(String url) {
        return new DiscordAlertAdapter(WebClient.builder(), url);
    }

    @Test
    @DisplayName("content 필드에 메시지를 담아 JSON으로 POST한다")
    void content_필드에_메시지를_담아_POST한다() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(204));

        adapterWithUrl(mockWebServer.url("/webhook").toString())
                .sendAlert("서킷 브레이커 장애 발생");

        RecordedRequest request = mockWebServer.takeRequest(3, TimeUnit.SECONDS);

        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/webhook");
        assertThat(request.getHeader("Content-Type")).contains("application/json");
        assertThat(request.getBody().readUtf8()).isEqualTo("{\"content\":\"서킷 브레이커 장애 발생\"}");
    }

    @Test
    @DisplayName("웹훅 URL이 비어 있으면 요청을 보내지 않는다")
    void 웹훅_URL이_비어있으면_요청을_보내지_않는다() throws Exception {
        adapterWithUrl("").sendAlert("무시되어야 하는 메시지");
        adapterWithUrl(null).sendAlert("무시되어야 하는 메시지");

        assertThat(mockWebServer.takeRequest(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mockWebServer.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("웹훅이 에러를 돌려줘도 호출자에게 예외를 던지지 않는다")
    void 웹훅이_에러를_돌려줘도_예외를_던지지_않는다() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        DiscordAlertAdapter adapter = adapterWithUrl(mockWebServer.url("/webhook").toString());

        // 알림 전송 실패가 본래 처리를 실패시켜서는 안 됩니다.
        adapter.sendAlert("전송은 실패하지만 예외는 나가지 않는다");

        assertThat(mockWebServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
    }
}
