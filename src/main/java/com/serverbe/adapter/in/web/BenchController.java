package com.serverbe.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * @responsibility 같은 외부 호출을 <b>논블로킹({@code WebClient})과 동기({@code RestClient})로 각각</b>
 * 수행하는 두 엔드포인트를 제공해, 톰캣 워커 점유량을 A/B로 측정할 수 있게 합니다.
 * @implSpec {@code bench} 프로파일에서만 뜹니다. 상용 경로에는 존재하지 않습니다.
 * @implNote <b>왜 별도 컨트롤러인가</b> — 이력서의 "스레드 사용률 감소"는 비교군이 있어야 성립하는데,
 * 이 저장소에는 {@code RestClient} 구현이 한 줄도 없습니다(마이그레이션이 실제로 일어난 적이 없습니다).
 * 생산 코드를 되돌리는 대신, 같은 스텁을 같은 타임아웃으로 부르는 두 경로를 프로파일 뒤에 두고
 * 프로파일만 바꿔 같은 부하를 두 번 흘립니다.
 * @implNote <b>두 클라이언트의 조건을 맞췄습니다.</b> {@code WebClient}는 {@code WebClientConfig}의
 * 빌더 빈을 {@code clone()}해 상용과 같은 커넥터(Reactor Netty, 5초 타임아웃)를 쓰고,
 * {@code RestClient}는 JDK {@code HttpClient} 기반으로 같은 5초 연결·응답 타임아웃을 겁니다.
 * 한쪽만 커넥션 풀이 없거나 타임아웃이 다르면 그 차이가 스레드 수로 둔갑합니다.
 * @implNote <b>커넥션 풀을 손대지 않은 것은 확인을 거친 판단입니다.</b> 한때 "Reactor Netty의
 * 기본 풀이 작아 논블로킹 쪽만 커넥션을 기다린다"고 보고 전용 풀을 끼웠지만, 상용 커넥터를 그대로
 * 쓰는 {@code GET /api/v1/geocode}가 동시 250에서 413 TPS를 대기 없이(평균 502ms, 오류 0%)
 * 처리하는 것을 확인해 되돌렸습니다. 첫 회차에서 양쪽 모두 타임아웃이 났던 진짜 원인은
 * <b>스텁(WireMock)이 지연 응답에 컨테이너 스레드를 붙잡아 TPS 37에서 포화된 것</b>이었습니다.
 * @implNote <b>동기 클라이언트를 HTTP/1.1 로 고정한 것은 측정에서 걸려 고친 것입니다.</b>
 * JDK {@code HttpClient} 의 기본 버전은 {@code HTTP_2} 이고 WireMock(Jetty)이 h2c 업그레이드를
 * 받아 주기 때문에, 아무것도 지정하지 않으면 동기 경로만 <b>커넥션 하나에 모든 요청을 다중화</b>
 * 합니다. 동시 100 까지는 티가 나지 않지만, Jetty 의 {@code SETTINGS_MAX_CONCURRENT_STREAMS}
 * 기본값(128)을 넘는 순간 JDK 클라이언트는 큐잉하지 않고 곧바로
 * {@code IOException: too many concurrent streams} 를 던집니다. 실제로 동시 300 측정에서
 * <b>오류율 80.7%</b> 가 나왔고, 그것은 "동기 방식의 한계" 가 아니라 클라이언트의 프로토콜 협상
 * 결과였습니다. 비교 대상인 {@code WebClient}(Reactor Netty)와 상용 경로가 모두 HTTP/1.1 을 쓰므로,
 * 대조군도 HTTP/1.1 로 맞추는 것이 "같은 조건" 입니다. 자세한 내용은
 * {@code docs/benchmark/01b-thread-occupancy-scale.md}.
 * @implNote 측정의 신뢰도를 위해 상용 엔드포인트({@code GET /api/v1/geocode})도 같은 스텁을 향하게
 * 띄웁니다({@code KAKAO_GEOCODING_DAPI} 덮어쓰기). 자세한 내용은
 * {@code docs/benchmark/01-thread-occupancy.md}.
 */
@Slf4j
@Tag(name = "Benchmark API", description = "스레드 점유 측정 전용. bench 프로파일에서만 노출됩니다.")
@RestController
@RequestMapping("/bench")
@Profile("bench")
public class BenchController {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final RestClient restClient;
    private final String stubPath;

    public BenchController(
            WebClient.Builder webClientBuilder,
            @Value("${bench.stub.url}") String stubBaseUrl,
            @Value("${bench.stub.path}") String stubPath
    ) {
        // 상용 어댑터들과 같은 방식: 공용 빌더 빈을 clone 해 커넥터 설정을 그대로 물려받는다.
        this.webClient = webClientBuilder.clone()
                .baseUrl(stubBaseUrl)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        // HTTP/1.1 을 명시한다. 기본값(HTTP_2)으로 두면 동시성이 올라가는 순간
                        // 측정이 무너진다 - 아래 @implNote 참고.
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(TIMEOUT)
                        .build());
        requestFactory.setReadTimeout(TIMEOUT);

        this.restClient = RestClient.builder()
                .baseUrl(stubBaseUrl)
                .requestFactory(requestFactory)
                .build();

        this.stubPath = stubPath;

        log.info("[Bench] 벤치마크 엔드포인트 활성화 - stub={}{}", stubBaseUrl, stubPath);
    }

    /**
     * 논블로킹 경로. 핸들러가 {@code Mono}를 반환하므로 Spring MVC가 비동기 서블릿으로 어댑팅하고,
     * 외부 응답을 기다리는 동안 톰캣 워커가 반납됩니다.
     */
    @Operation(summary = "논블로킹(WebClient) 외부 호출")
    @GetMapping("/reactive")
    public Mono<ResponseEntity<String>> reactive() {
        return webClient.get()
                .uri(stubPath)
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok);
    }

    /**
     * 동기 경로. 외부 응답이 올 때까지 톰캣 워커 스레드가 그대로 묶여 있습니다.
     * 이것이 측정하려는 비교군입니다.
     */
    @Operation(summary = "동기(RestClient) 외부 호출")
    @GetMapping("/blocking")
    public ResponseEntity<String> blocking() {
        String body = restClient.get()
                .uri(stubPath)
                .retrieve()
                .body(String.class);

        return ResponseEntity.ok(body);
    }
}
