package com.serverbe.application.service;

import com.serverbe.application.port.out.art.RunningArtRedisPort;
import com.serverbe.application.port.out.jpa.RunningArtRepositoryPort;
import com.serverbe.domain.exception.server.ServerErrorCode;
import com.serverbe.domain.exception.server.ServerException;
import com.serverbe.domain.model.art.RunningArt;
import com.serverbe.domain.model.art.vo.Proficiency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RunningArtRegistrationService}는 {@link com.serverbe.application.port.in.art.RegisterCompletedArtUseCase}만
 * 구현합니다. 호출자가 사용자가 아니라 AI 결과 처리 흐름이라는 점이 이 클래스가 따로 있는 이유입니다.
 */
@ExtendWith(MockitoExtension.class)
class RunningArtRegistrationServiceTest {

    @Mock
    private RunningArtRepositoryPort repositoryPort;
    @Mock
    private RunningArtRedisPort runningArtRedisPort;

    @InjectMocks
    private RunningArtRegistrationService runningArtRegistrationService;

    private static final Long OWNER_ID = 1L;

    @Test
    @DisplayName("성공: 유효한 폴리라인을 등록하면 메타데이터를 추출해 DB/Redis에 저장하고 생성된 ID를 반환한다")
    void registerFromPolyline_Success() {
        // given: 구글 Encoded Polyline 알고리즘의 표준 예시 문자열 (3개 좌표 포함)
        String validPolyline = "_p~iF~ps|U_ulLnnqC_mqNvxq`@";
        Long savedArtId = 777L;
        given(repositoryPort.save(any(RunningArt.class))).willAnswer(invocation -> {
            RunningArt art = invocation.getArgument(0);
            return art.toBuilder().id(savedArtId).build();
        });
        given(runningArtRedisPort.saveLocation(eq(savedArtId), any(), any())).willReturn(Mono.just(1L));

        // when
        Long resultId = runningArtRegistrationService.registerFromPolyline(OWNER_ID, validPolyline, "AI 생성 코스", "HEART", Proficiency.BEGINNER);

        // then
        assertThat(resultId).isEqualTo(savedArtId);
        verify(runningArtRedisPort).saveLocation(eq(savedArtId), any(), any());
    }

    @Test
    @DisplayName("실패: 빈 폴리라인 문자열을 등록하려고 하면 파싱 예외가 발생하고 DB에는 아무것도 저장되지 않는다")
    void registerFromPolyline_Fail_EmptyPolyline() {
        // when & then
        assertThatThrownBy(() -> runningArtRegistrationService.registerFromPolyline(OWNER_ID, "", "AI 생성 코스", "HEART", Proficiency.BEGINNER))
                .isInstanceOf(ServerException.class)
                .hasFieldOrPropertyWithValue("errorCode", ServerErrorCode.POLYLINE_PARSE_ERROR);

        verify(repositoryPort, never()).save(any());
        verify(runningArtRedisPort, never()).saveLocation(any(), any(), any());
    }
}
