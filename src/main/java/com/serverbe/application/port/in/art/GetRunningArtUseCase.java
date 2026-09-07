package com.serverbe.application.port.in.art;

import com.serverbe.application.port.in.dto.art.RunningArtResult;
import com.serverbe.domain.model.art.RunningArt;
import com.serverbe.application.port.dto.PageQuery;
import com.serverbe.application.port.dto.PageResult;

import java.util.List;

public interface GetRunningArtUseCase {
    /**
     * @param userId 런닝 아트를 조회할 사용자의 ID
     * @param pageQuery 페이징·정렬 정보
     * @return 런닝 아트 정보 DTO
     * @implNote 사용자의 ID로 런닝 아트를 조회하고 일치하는 항목들만 가져온다.
     * @implSpec 조회에 순수 JPA만 사용한다. 추후 필요하다면 Querydsl의 Projection 기능을 사용해서 필요한 필드만 가져올 수 있도록 구현해야 한다.
     * @requirement UC-ART-03: 사용자의 전체 런닝 아트 조회 요청
     */
    PageResult<RunningArtResult> getRunningArtsByUserId(Long userId, PageQuery pageQuery);

    /**
     * @param userId       사용자의 것인지 검증하기 위한 PK
     * @param runningArtId 조회할 런닝 아트의 PK
     * @return 조회한 런닝 아트 데이터
     * @responsibility 사용자의 런닝 아트를 조회한다.
     * @requirement UC-ART-02: 런닝 아트 단건 조회 요청
     */
    RunningArtResult getRunningArtById(Long userId, Long runningArtId);
}