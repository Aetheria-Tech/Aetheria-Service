package com.serverbe.application.service;

import com.serverbe.application.port.in.art.RegisterCompletedArtUseCase;
import com.serverbe.application.port.out.art.RunningArtRedisPort;
import com.serverbe.application.port.out.jpa.RunningArtRepositoryPort;
import com.serverbe.domain.model.art.RunningArt;
import com.serverbe.domain.model.art.vo.Proficiency;
import com.serverbe.domain.util.PolylineUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @responsibility AI 생성이 끝난 폴리라인을 런닝 아트로 등록합니다.
 * @implSpec {@link RunningArtService}에서 갈라져 나왔습니다. <b>호출자가 사용자가 아니라 AI 결과
 * 처리 흐름</b>이라는 점이 갈라선 이유입니다 — 지금 유일한 호출자는
 * {@link AiResultRetrievalService}이고, 사용자가 여는 CRUD 엔드포인트와는 진입 경로가 다릅니다.
 * @implNote <b>알려진 불일치가 하나 있습니다.</b> 삭제 경로({@link RunningArtService})는 Redis GEO
 * 갱신을 {@code afterCommit}으로 미뤄 커밋이 성공한 뒤에만 반영하는데, 이 클래스는 트랜잭션
 * <b>안에서</b> {@code saveLocation}을 부릅니다. 커밋이 깨지면 존재하지 않는 아트의 GEO 항목이
 * Redis에 남습니다. 지금은 실패 시 배치 복구를 전제로 두고 있으며, 두 경로의 규율을 맞추는 것은
 * 동작 변경이라 별도 항목으로 다룹니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunningArtRegistrationService implements RegisterCompletedArtUseCase {

    private final RunningArtRepositoryPort repositoryPort;
    private final RunningArtRedisPort runningArtRedisPort;

    /**
     * @responsibility 비동기 AI 작업이 완료된 후, 전달받은 GPX 데이터를 바탕으로 런닝아트를 생성하고 DB/Redis에 등록합니다.
     * @implNote 이 유스케이스는 이벤트 루프가 아니라 <b>블로킹 워커 스레드</b>에서 동기적으로 호출됩니다
     * (지금 그 경로는 AI 결과 통보를 소비하는 SQS 워커입니다). 그래서 Redis GEO 동기화만 {@code block()}으로
     * 받아도 논블로킹 스레드를 굶기지 않습니다. 호출 경로가 바뀌어 이벤트 루프에서 불리게 되면
     * <b>이 전제가 먼저 깨집니다.</b>
     */
    @Override
    @Transactional
    public Long registerFromPolyline(Long userId, String polyline, String title, String shape, Proficiency proficiency) {

        // 1. Polyline에서 메타데이터(시작 좌표, 거리 등) 추출
        PolylineUtils.PolylineMetadata metadata = PolylineUtils.extractMetadata(polyline);

        // 2. 도메인 엔티티 조립
        RunningArt runningArt = RunningArt.builder()
                .userId(userId)
                .title(title)
                .gpx(polyline) // 변수명은 gpx지만 실제론 polyline 데이터가 들어갑니다.
                .content("AI 생성 런닝 아트")
                .shape(shape)
                .proficiency(proficiency)
                .distance(metadata.totalDistanceMeters())
                .startLat(metadata.startLat())
                .startLon(metadata.startLon())
                .build();

        // 3. DB 저장 (JPA Blocking 방식)
        RunningArt savedArt = repositoryPort.save(runningArt);
        log.info("DB 런닝아트 저장 완료 (ArtId: {})", savedArt.id());

        // 4. Redis 동기화 (기존 비동기 코드를 여기서만 살짝 block 해줍니다)
        try {
            runningArtRedisPort.saveLocation(savedArt.id(), savedArt.startLat(), savedArt.startLon())
                    .block(); // 블로킹 워커 스레드에서 호출되므로 안전합니다 (근거는 위 @implNote)
            log.info("Redis 동기화 완료 (ArtId: {})", savedArt.id());
        } catch (Exception e) {
            // Redis 저장이 실패해도 DB 저장을 롤백시키지 않으려면 try-catch로 감싸줍니다.
            log.error("Redis GEO 동기화 실패 (ArtId={}): 나중에 배치로 복구해야 합니다.", savedArt.id(), e);
        }

        // 5. 최종 ID 반환!
        return savedArt.id();
    }
}
