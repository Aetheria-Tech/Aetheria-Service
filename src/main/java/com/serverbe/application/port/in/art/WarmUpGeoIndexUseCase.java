package com.serverbe.application.port.in.art;

/**
 * @responsibility 저장소의 위치 데이터를 공간 인덱스로 다시 적재하는 인바운드 포트입니다.
 * @implSpec 적재 전에 기존 인덱스를 비워야 합니다. DB에서 지워졌지만 인덱스에 남은 유령 데이터가
 * 검색 결과에 섞이면, 존재하지 않는 런닝 아트가 목록에 뜹니다.
 */
public interface WarmUpGeoIndexUseCase {

    /**
     * 공간 인덱스를 비우고 저장소의 현재 위치 데이터로 다시 채웁니다.
     */
    void warmUpGeoIndex();
}
