package com.serverbe.application.port.out.jpa;

import com.serverbe.application.port.in.dto.art.RunningArtLocationDto;
import com.serverbe.application.port.in.dto.art.RunningArtUpdateCommand;
import com.serverbe.domain.model.art.RunningArt;
import com.serverbe.application.port.dto.PageQuery;
import com.serverbe.application.port.dto.PageResult;

import java.util.List;
import java.util.Optional;

/**
 * @responsibility 런닝아트(RunningArt) 도메인 모델의 영속성을 관리하기 위한 아웃바운드 포트 인터페이스입니다.
 * 도메인 레이어와 인프라 레이어 간의 결합도를 낮추고 데이터 저장 및 조회를 위한 규격(Contract)을 정의합니다.
 */
public interface RunningArtRepositoryPort {

    /**
     * @responsibility 전달받은 런닝아트 도메인 엔티티를 영구 저장소에 저장하거나 갱신합니다.
     * @param runningArt 저장할 {@link RunningArt} 도메인 모델
     * @return 저장 프로세스가 완료되어 식별자가 부여되거나 갱신된 {@link RunningArt} 도메인 모델
     */
    RunningArt save(RunningArt runningArt);

    /**
     * @responsibility 식별자(ID)를 통해 특정 런닝아트 정보를 조회합니다.
     * @param id 조회하고자 하는 런닝아트의 고유 식별자
     * @return 존재 여부를 보장할 수 없는 조회 결과를 담은 {@link Optional<RunningArt>}
     */
    Optional<RunningArt> findById(Long id);

    /**
     * @responsibility 특정 사용자가 소유한 런닝아트 목록을 페이지 단위로 조회합니다.
     * @param userId    조회의 기준이 되는 사용자의 고유 식별자
     * @param pageQuery 페이징·정렬 정보
     * @return 해당 사용자와 연관된 {@link RunningArt} 페이지
     */
    PageResult<RunningArt> findByUserId(Long userId, PageQuery pageQuery);

    /**
     * @responsibility 고유 식별자를 기준으로 특정 런닝아트 데이터를 영구 삭제합니다.
     * @param runningArtId 삭제할 런닝아트의 고유 식별자
     */
    void deleteById(Long runningArtId);

    /**
     * @responsibility 특정 사용자와 연관된 모든 런닝아트 데이터를 일괄 삭제합니다.
     * @param userId 삭제 대상이 되는 사용자의 고유 식별자
     */
    void deleteByUserId(Long userId);

    /**
     * @responsibility 특정 런닝아트의 메타데이터(제목, 내용 등)를 수정합니다.
     * @param runningArtId 수정할 런닝아트의 고유 식별자
     * @param dto 수정될 정보를 포함하고 있는 {@link RunningArtUpdateCommand} DTO
     */
    void updateMetadata(Long runningArtId, RunningArtUpdateCommand dto);


    List<RunningArt> findAllByIdIn(List<Long> ids);

    List<Long> findIdsByUserId(Long userId);

    List<RunningArtLocationDto> findAllLocations();
}