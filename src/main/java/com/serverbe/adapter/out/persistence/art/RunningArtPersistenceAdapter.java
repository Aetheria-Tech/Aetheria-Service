package com.serverbe.adapter.out.persistence.art;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.serverbe.adapter.out.persistence.mapper.RunningArtMapper;
import com.serverbe.adapter.out.persistence.user.JpaUserRepository;
import com.serverbe.adapter.out.persistence.user.UserEntity;
import com.serverbe.application.port.in.dto.art.RunningArtLocationDto;
import com.serverbe.application.port.in.dto.art.RunningArtUpdateCommand;
import com.serverbe.application.port.out.jpa.RunningArtRepositoryPort;
import com.serverbe.domain.model.art.RunningArt;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import com.querydsl.core.types.Projections;
import com.serverbe.application.port.dto.PageQuery;
import com.serverbe.application.port.dto.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


import static com.serverbe.adapter.out.persistence.art.QRunningArtEntity.runningArtEntity;

/**
 * @author Duskafka
 * @responsibility 런닝아트 도메인 모델과 데이터베이스 엔티티 간의 매핑 및 데이터 영속화를 담당하는 아웃바운드 어댑터입니다.
 * @implSpec Spring Data JPA를 사용하여 데이터베이스와 상호작용하며, 도메인 레이어의 인터페이스인 {@link RunningArtRepositoryPort}를 구현합니다.
 * @see RunningArtRepositoryPort
 */
@Repository
@RequiredArgsConstructor
public class RunningArtPersistenceAdapter implements RunningArtRepositoryPort {

    private final JPAQueryFactory queryFactory;
    private final JpaRunningArtRepository jpaRepository;
    private final JpaUserRepository jpaUserRepository;
    private final RunningArtMapper mapper;

    /**
     * @param runningArt 저장할 런닝아트 도메인 모델
     * @return 저장된 정보를 포함하는 {@link RunningArt} 도메인 모델
     * @responsibility 새로운 런닝아트 정보를 저장하거나 기존 정보를 업데이트합니다.
     * @implSpec {@link JpaUserRepository#getReferenceById(Object)}를 사용하여 연관된 사용자 엔티티의 프록시를 가져온 후, 매퍼를 통해 엔티티로 변환하여 저장합니다.
     * @implNote 실제 DB 조회를 피하기 위해 getReferenceById를 사용하므로, 해당 ID의 사용자가 존재하지 않을 경우 저장 시점에 예외가 발생할 수 있습니다.
     */
    @Override
    public RunningArt save(RunningArt runningArt) {
        // 1. 도메인의 userId를 이용해 UserEntity 참조를 가져옴
        UserEntity userEntity = jpaUserRepository.getReferenceById(runningArt.userId());

        // 2. Mapper를 통해 Domain -> Entity 변환 (UserEntity 주입)
        RunningArtEntity entity = mapper.toEntity(runningArt, userEntity);

        // 3. DB 저장 및 결과를 다시 도메인으로 변환하여 반환
        RunningArtEntity savedEntity = jpaRepository.save(entity);
        return mapper.toDomain(savedEntity);
    }

    /**
     * @param id 조회할 런닝아트의 고유 ID
     * @return 조회 결과를 포함하는 {@link Optional<RunningArt>}
     * @responsibility 특정 식별자를 가진 런닝아트를 조회합니다.
     * @implSpec {@link JpaRunningArtRepository#findById(Object)}를 호출하고 결과를 도메인 모델로 변환합니다.
     */
    @Override
    public Optional<RunningArt> findById(Long id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    /**
     * @param pageable 페이징 정보
     * @return 조회된 {@link RunningArt} 도메인 페이지
     * @responsibility 저장된 모든 런닝아트 목록을 조회합니다.
    /**
     * @param runningArtId 수정할 런닝아트 ID
     * @param dto          수정할 정보를 담은 {@link RunningArtUpdateCommand} DTO
     * @responsibility 런닝아트의 메타데이터(제목, 내용)를 수정합니다.
     * @implSpec 영속성 컨텍스트 내의 엔티티를 조회한 후 엔티티 내부의 수정 메서드를 호출하여 Dirty Checking에 의해 업데이트가 수행되도록 합니다.
     * @implNote 해당 ID의 데이터가 없을 경우 {@link EntityNotFoundException}을 발생시킵니다.
     */
    @Override
    public void updateMetadata(Long runningArtId, RunningArtUpdateCommand dto) {
        RunningArtEntity entity = jpaRepository.findById(runningArtId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "업데이트 실패: 해당 런닝아트를 DB에서 찾을 수 없습니다. (ID: " + runningArtId + ")"
                ));

        entity.updateMetadata(dto.title(), dto.content());
    }

    /**
     * @param ids 조회하고자 하는 런닝 아트의 고유 식별자 리스트
     * @return 조회된 {@link RunningArt} 도메인 모델 리스트
     * @responsibility 다수의 ID를 입력받아 해당하는 런닝 아트 상세 정보를 데이터베이스에서 한 번에 조회합니다.
     * @implSpec {@link JpaRunningArtRepository#findAllById(Iterable)}를 사용하여 SQL의 `IN` 절을 생성하고 배치(Batch) 조회를 수행합니다. 조회된 엔티티는 매퍼를 통해 도메인 모델로 변환됩니다.
     * @implNote RDBMS의 특성상 반환되는 리스트의 순서는 입력된 {@code ids} 리스트의 순서(예: 거리순 정렬)와 일치하지 않을 수 있습니다.
     */
    @Override
    public List<RunningArt> findAllByIdIn(List<Long> ids) {
        return jpaRepository.findAllById(ids).stream()
                .map(mapper::toDomain)
                .toList();
    }

    /**
     * @param userId 조회할 사용자의 고유 ID
     * @return 런닝아트의 고유 식별자(PK) 리스트
     * @responsibility 특정 사용자가 소유한 런닝 아트의 식별자 목록만 고속으로 조회합니다.
     * @implSpec JPA의 @Query를 사용하여 엔티티 전체를 로딩하지 않고 ID 컬럼만 Projection하여 조회 성능을 최적화합니다.
     * @implNote 전체 삭제 전 Redis 데이터 동기화 등 가벼운 식별자 목록이 필요할 때 사용합니다.
     */
    @Override
    public List<Long> findIdsByUserId(Long userId) {
        return jpaRepository.findIdsByUserId(userId);
    }

    /**
     * @return 런닝아트의 위치 정보(ID, 위도, 경도)를 담은 {@link RunningArtLocationDto} 리스트
     * @responsibility Redis GEO 공간 인덱스 동기화(Warm-up) 등을 위해 전체 런닝아트의 좌표 데이터만 고속으로 추출합니다.
     * @implSpec QueryDSL의 QDto를 활용하여 엔티티 전체 로딩 없이 필수 컬럼(ID, Lat, Lon)만 타입 세이프(Type-safe)하게 프로젝션하며, 좌표 값이 없는 비정상 데이터는 필터링합니다.
     * @implNote 영속성 컨텍스트를 거치지 않고 순수 DTO로 반환되므로, 대용량 데이터 조회 시 메모리 낭비와 쿼리 부하를 극적으로 줄입니다.
     */
    @Override
    public List<RunningArtLocationDto> findAllLocations() {
        return queryFactory
                // QDto를 사용해 Type-safe하게 데이터 매핑
                // 포트 DTO에 @QueryProjection을 달지 않기 위해 생성자 프로젝션을 쓴다.
                .select(Projections.constructor(
                        RunningArtLocationDto.class,
                        runningArtEntity.id,
                        runningArtEntity.startLat,
                        runningArtEntity.startLon
                ))
                .from(runningArtEntity)
                // 방어 로직: 좌표가 없는 비정상 데이터는 제외
                .where(
                        runningArtEntity.startLat.isNotNull(),
                        runningArtEntity.startLon.isNotNull()
                )
                .fetch();
    }

    /**
     * @param runningArtId 삭제할 런닝아트 ID
     * @responsibility 고유 ID를 기준으로 런닝아트를 삭제합니다.
     * @implSpec {@link JpaRunningArtRepository#deleteById(Object)}를 호출하여 삭제 작업을 수행합니다.
     */
    @Override
    public void deleteById(Long runningArtId) {
        jpaRepository.deleteById(runningArtId);
    }

    /**
     * @param userId 삭제할 대상 사용자의 고유 ID
     * @responsibility 특정 사용자가 생성한 모든 런닝아트를 삭제합니다.
     * @implSpec 사용자 ID를 기반으로 대량 삭제 요청을 JPA 리포지토리에 전달합니다.
     * @implNote 대량 삭제 시 영속성 컨텍스트의 상태와 DB 상태 간의 불일치에 주의해야 하며, 필요시 쿼리 메서드에 @Modifying을 사용합니다.
     */
    @Override
    public void deleteByUserId(Long userId) {
        jpaRepository.deleteByUserId(userId);
    }

    /**
     * @param userId    조회할 사용자의 고유 ID
     * @param pageQuery 페이징·정렬 정보
     * @return 해당 사용자의 {@link PageResult} 목록
     * @responsibility 특정 사용자가 생성한 런닝아트 목록을 조회합니다.
     * @implSpec 엔티티 간의 연관 관계 필드(User)를 기반으로 명명 규칙에 따른 JPA 쿼리 메서드를 사용합니다.
     * @implNote Spring Data의 {@link Pageable}은 이 어댑터 밖으로 나가지 않습니다. 포트는 저장소
     * 기술을 모르는 {@link PageQuery}만 주고받습니다.
     */
    @Override
    public PageResult<RunningArt> findByUserId(Long userId, PageQuery pageQuery) {
        Page<RunningArtEntity> page = jpaRepository.findByUser_Id(userId, toPageable(pageQuery));

        return new PageResult<>(
                page.getContent().stream().map(mapper::toDomain).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()
        );
    }

    /**
     * @param pageQuery 애플리케이션 계층의 페이징·정렬 요청
     * @return Spring Data가 이해하는 {@link Pageable}
     */
    private static Pageable toPageable(PageQuery pageQuery) {
        if (pageQuery.sorts().isEmpty()) {
            return PageRequest.of(pageQuery.page(), pageQuery.size());
        }

        Sort sort = Sort.by(pageQuery.sorts().stream()
                .map(order -> new Sort.Order(
                        order.direction() == PageQuery.Direction.ASC ? Sort.Direction.ASC : Sort.Direction.DESC,
                        order.property()))
                .toList());

        return PageRequest.of(pageQuery.page(), pageQuery.size(), sort);
    }
}