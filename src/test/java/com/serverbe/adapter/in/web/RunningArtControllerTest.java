package com.serverbe.adapter.in.web;

import com.serverbe.application.port.in.art.*;
import com.serverbe.application.port.in.dto.art.RunningArtResult;
import com.serverbe.domain.model.art.vo.Proficiency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.BDDMockito.given;

// 컨트롤러 빈(Bean)과 Security 설정만 가볍게 띄우는 슬라이스 테스트 어노테이션
@WebFluxTest(RunningArtController.class)
class RunningArtControllerTest {

    @Autowired
    private WebTestClient webTestClient; // 비동기 논블로킹 API 테스트를 위한 클라이언트

    // 컨트롤러가 의존하는 모든 UseCase를 가짜(Mock) 객체로 주입
    // 참고: 런닝 아트 생성은 더 이상 이 컨트롤러가 아닌 AI 생성 파이프라인(AiGenerationController)이 담당하므로
    // CreateRunningArtUseCase는 RunningArtController의 의존성에서 제외되었습니다.
    @MockitoBean private GetRunningArtUseCase getRunningArtUseCase;
    @MockitoBean private DeleteRunningArtUseCase deleteRunningArtUseCase;
    @MockitoBean private UpdateRunningArtUseCase updateRunningArtUseCase;
    @MockitoBean private GetNearbyRunningArtUseCase getNearbyRunningArtUseCase;

    @Test
    @WithMockUser // 가짜 인증된 유저(Security Context) 주입
    @DisplayName("GET /nearby - 쿼리 파라미터로 위도/경도를 전달하면 반경 내 런닝 아트 목록을 JSON으로 반환한다")
    void getNearbyArts_Success() {
        // given: UseCase가 반환할 가짜 데이터 설정 (비즈니스 로직이 성공했다고 가정)
        // 주의: 실제 레코드/클래스 생성자 방식에 맞게 조정해 주세요. (빌더가 있다면 빌더 사용)
        RunningArtResult mockResult = new RunningArtResult(
                1L,
                "댕댕런 하트 코스",
                "설명입니다",
                "HEART",
                Proficiency.EXPERT, // 참고: Proficiency enum에 MASTER는 존재하지 않아 EXPERT(최고 등급)로 대체
                "gpx_data_...",
                100L
        );

        // 서비스가 Flux 스트림으로 1개의 결과를 반환하도록 조작
        given(getNearbyRunningArtUseCase.getNearbyArts(anyDouble(), anyDouble(), anyDouble()))
                .willReturn(Flux.just(mockResult));

        // when & then: 가상의 HTTP 요청 발송 및 응답 검증
        webTestClient
                // 1. HTTP GET 요청 구성
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/running-arts/nearby")
                        .queryParam("lat", 37.323)
                        .queryParam("lon", 127.106)
                        .queryParam("radius", 5.0)
                        .build())
                .exchange() // 요청 실행!
                // 2. HTTP 상태 코드 검증
                .expectStatus().isOk() // 200 OK
                // 3. 응답 Body(JSON) 검증
                .expectBody()
                .jsonPath("$.data").isArray() // RestApiResponse의 data 필드가 배열인지 확인
                .jsonPath("$.data[0].id").isEqualTo(1) // 첫 번째 요소의 ID가 1인지
                .jsonPath("$.data[0].title").isEqualTo("댕댕런 하트 코스") // DTO 변환이 잘 되었는지
                .jsonPath("$.data[0].shape").isEqualTo("HEART");
    }
}