package com.serverbe.adapter.out.persistence.art;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.serverbe.adapter.out.persistence.mapper.RunningArtMapper;
import com.serverbe.adapter.out.persistence.user.JpaUserRepository;
import com.serverbe.adapter.out.persistence.user.UserEntity;
import com.serverbe.application.port.dto.PageQuery;
import com.serverbe.application.port.dto.PageResult;
import com.serverbe.application.port.in.dto.art.RunningArtUpdateCommand;
import com.serverbe.domain.model.art.RunningArt;
import com.serverbe.domain.model.art.vo.Proficiency;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * @responsibility 소유자가 실제로 쿼리에 들어가는지와 <b>{@code PageQuery} → {@code Pageable} 변환</b>을
 * 고정합니다.
 * @implSpec 페이징 계약은 세 곳으로 나뉘어 있습니다 — {@code PageQueryMapperTest}가 <b>웹 요청 →
 * {@code PageQuery}</b> 를, {@code RunningArtPageJsonContractTest}가 <b>응답 JSON 모양</b>을 봅니다.
 * 그 사이의 <b>{@code PageQuery} → {@code Pageable} → {@code PageResult}</b> 구간은 이 어댑터
 * 안에만 있어 지금까지 아무도 보지 않았습니다. <b>포트가 {@code Pageable}을 모른다는 결정이 실제로
 * 지켜지는 지점이 여기입니다.</b>
 * @implNote {@code findAllLocations}는 Querydsl fluent 체인이라 덮지 않습니다. 목으로 흉내 내면
 * 테스트가 프로덕션 코드의 호출 순서를 그대로 베낀 것이 됩니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("런닝 아트 영속성 어댑터")
class RunningArtPersistenceAdapterTest {

    @Mock
    private JPAQueryFactory queryFactory;
    @Mock
    private JpaRunningArtRepository jpaRepository;
    @Mock
    private JpaUserRepository jpaUserRepository;
    @Mock
    private RunningArtMapper mapper;

    @Mock
    private RunningArtEntity entity;
    @Mock
    private UserEntity userEntity;

    @InjectMocks
    private RunningArtPersistenceAdapter adapter;

    private static final Long USER_ID = 42L;
    private static final Long ART_ID = 7L;

    private static RunningArt art() {
        return new RunningArt(
                ART_ID, "제목", "내용", "고양이", 5.0,
                Proficiency.BEGINNER, "<gpx/>", USER_ID, 37.5, 127.0);
    }

    @Nested
    @DisplayName("소유자 — userId가 실제로 저장소까지 간다")
    class 소유자 {

        /**
         * @implNote {@code getReferenceById}는 DB를 조회하지 않고 프록시만 만듭니다. 그 프록시가
         * <b>매퍼까지 전달되어야</b> 엔티티의 연관 관계가 채워집니다. 여기가 끊기면 저장은
         * 성공하는데 소유자만 비는 형태로 조용히 깨집니다.
         */
        @Test
        @DisplayName("저장은 userId로 얻은 사용자 프록시를 매퍼에 넘긴다")
        void 저장은_사용자_프록시를_매퍼에_넘긴다() {
            RunningArt expected = art();

            given(jpaUserRepository.getReferenceById(USER_ID)).willReturn(userEntity);
            given(mapper.toEntity(any(RunningArt.class), eq(userEntity))).willReturn(entity);
            given(jpaRepository.save(entity)).willReturn(entity);
            given(mapper.toDomain(entity)).willReturn(expected);

            assertThat(adapter.save(art())).isSameAs(expected);

            verify(jpaUserRepository).getReferenceById(USER_ID);
        }

        @Test
        @DisplayName("사용자별 목록 조회는 그 userId를 그대로 저장소에 넘긴다")
        void 사용자별_목록은_userId를_그대로_넘긴다() {
            given(jpaRepository.findByUser_Id(eq(USER_ID), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            adapter.findByUserId(USER_ID, PageQuery.of(0, 10));

            verify(jpaRepository).findByUser_Id(eq(USER_ID), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("페이징 — PageQuery가 Pageable로 옮겨지고 PageResult로 돌아온다")
    class 페이징 {

        private Pageable capturePageable() {
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(jpaRepository).findByUser_Id(eq(USER_ID), pageable.capture());
            return pageable.getValue();
        }

        @Test
        @DisplayName("정렬이 없으면 정렬 없는 페이지 요청이 된다")
        void 정렬이_없으면_정렬_없는_요청이_된다() {
            given(jpaRepository.findByUser_Id(eq(USER_ID), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            adapter.findByUserId(USER_ID, PageQuery.of(2, 20));

            Pageable pageable = capturePageable();
            assertThat(pageable.getPageNumber()).isEqualTo(2);
            assertThat(pageable.getPageSize()).isEqualTo(20);
            assertThat(pageable.getSort().isSorted()).isFalse();
        }

        /**
         * @implNote 방향이 뒤집혀도 컴파일되고 예외도 나지 않습니다 — 목록의 <b>순서만</b>
         * 조용히 바뀝니다. 그래서 두 방향을 모두 고정합니다.
         */
        @Test
        @DisplayName("정렬 방향이 스프링 쪽으로 그대로 옮겨진다")
        void 정렬_방향이_그대로_옮겨진다() {
            given(jpaRepository.findByUser_Id(eq(USER_ID), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            adapter.findByUserId(USER_ID, new PageQuery(0, 10, List.of(
                    new PageQuery.SortOrder("createdAt", PageQuery.Direction.DESC),
                    new PageQuery.SortOrder("title", PageQuery.Direction.ASC))));

            Sort sort = capturePageable().getSort();
            assertThat(sort.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
            assertThat(sort.getOrderFor("title").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        /**
         * @implNote {@code totalElements}는 <b>현재 페이지의 개수가 아니라 전체 개수</b>입니다.
         * 여기서 페이지 크기를 넣으면 마지막 페이지가 영영 나오지 않습니다.
         */
        @Test
        @DisplayName("결과는 내용과 페이지 메타데이터를 함께 담아 돌아온다")
        void 결과는_페이지_메타데이터를_함께_담는다() {
            RunningArt expected = art();
            given(jpaRepository.findByUser_Id(eq(USER_ID), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(entity), PageRequest.of(1, 5), 42));
            given(mapper.toDomain(entity)).willReturn(expected);

            PageResult<RunningArt> result = adapter.findByUserId(USER_ID, PageQuery.of(1, 5));

            assertThat(result.content()).containsExactly(expected);
            assertThat(result.page()).isEqualTo(1);
            assertThat(result.size()).isEqualTo(5);
            assertThat(result.totalElements()).isEqualTo(42);
        }
    }

    @Nested
    @DisplayName("나머지 조회와 변경")
    class 나머지 {

        /**
         * @implNote 빈 리스트가 그대로 `IN ()` 으로 나가면 저장소마다 동작이 갈립니다. 지금은
         * Spring Data가 빈 결과를 돌려주므로 <b>매퍼가 한 번도 불리지 않는 것</b>까지 고정합니다.
         */
        @Test
        @DisplayName("빈 ID 목록으로 조회하면 빈 결과이고 매퍼를 부르지 않는다")
        void 빈_ID_목록은_빈_결과다() {
            given(jpaRepository.findAllById(anyList())).willReturn(List.of());

            assertThat(adapter.findAllByIdIn(List.of())).isEmpty();

            verify(mapper, never()).toDomain(any(RunningArtEntity.class));
        }

        @Test
        @DisplayName("ID 목록 조회는 엔티티를 도메인으로 옮긴다")
        void ID_목록_조회는_도메인으로_옮긴다() {
            RunningArt expected = art();
            given(jpaRepository.findAllById(List.of(ART_ID))).willReturn(List.of(entity));
            given(mapper.toDomain(entity)).willReturn(expected);

            assertThat(adapter.findAllByIdIn(List.of(ART_ID))).containsExactly(expected);
        }

        @Test
        @DisplayName("단건 조회는 매퍼를 거치고, 없으면 빈 Optional이다")
        void 단건_조회는_매퍼를_거친다() {
            RunningArt expected = art();
            given(jpaRepository.findById(ART_ID)).willReturn(Optional.of(entity), Optional.empty());
            given(mapper.toDomain(entity)).willReturn(expected);

            assertThat(adapter.findById(ART_ID)).contains(expected);
            assertThat(adapter.findById(ART_ID)).isEmpty();
        }

        /**
         * @implNote 수정은 저장 호출 없이 <b>dirty checking</b>으로 끝납니다. 엔티티의 수정
         * 메서드를 부르는 것이 전부이고, 여기에 {@code save}를 추가하면 의미 없는 문장이 하나 더
         * 나갑니다.
         */
        @Test
        @DisplayName("메타데이터 수정은 엔티티에 위임하고 저장을 부르지 않는다")
        void 메타데이터_수정은_엔티티에_위임한다() {
            given(jpaRepository.findById(ART_ID)).willReturn(Optional.of(entity));

            adapter.updateMetadata(ART_ID, new RunningArtUpdateCommand("새 제목", "새 내용"));

            verify(entity).updateMetadata("새 제목", "새 내용");
            verify(jpaRepository, never()).save(any(RunningArtEntity.class));
        }

        @Test
        @DisplayName("수정 대상이 없으면 예외를 던진다")
        void 수정_대상이_없으면_예외를_던진다() {
            given(jpaRepository.findById(ART_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> adapter.updateMetadata(ART_ID, new RunningArtUpdateCommand("t", "c")))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(String.valueOf(ART_ID));
        }

        @Test
        @DisplayName("식별자 목록 조회와 삭제는 저장소에 그대로 위임한다")
        void 식별자_조회와_삭제는_그대로_위임한다() {
            given(jpaRepository.findIdsByUserId(USER_ID)).willReturn(List.of(ART_ID));

            assertThat(adapter.findIdsByUserId(USER_ID)).containsExactly(ART_ID);

            adapter.deleteById(ART_ID);
            adapter.deleteByUserId(USER_ID);

            verify(jpaRepository).deleteById(ART_ID);
            verify(jpaRepository).deleteByUserId(USER_ID);
            verifyNoInteractions(mapper);
        }
    }
}
