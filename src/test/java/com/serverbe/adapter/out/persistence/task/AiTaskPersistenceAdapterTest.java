package com.serverbe.adapter.out.persistence.task;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.serverbe.adapter.out.persistence.mapper.AiTaskMapper;
import com.serverbe.domain.exception.ai.AiErrorCode;
import com.serverbe.domain.exception.ai.AiException;
import com.serverbe.domain.model.art.vo.Proficiency;
import com.serverbe.domain.model.task.AiTask;
import com.serverbe.domain.model.task.vo.TaskStatus;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * @responsibility 어댑터 경계에서 일어나는 <b>예외 번역</b>과 <b>매퍼 왕복</b>을 고정합니다.
 * @implSpec 가장 중요한 것은 유니크 제약 위반의 번역입니다. Redis 락과 중복 조회를 통과한 동시
 * 요청이 <b>여기서 최종적으로 걸러지고</b>, 그 분기가 사라져도 정상 경로는 멀쩡히 돌기 때문에
 * 테스트가 아니면 아무도 모릅니다.
 * @implNote 본보기는 {@code UserPersistenceAdapterTest}입니다 — 매퍼를 목으로 두고 왕복과 예외
 * 번역을 봅니다. {@code findZombieTasks}는 Querydsl fluent 체인이라 여기서 덮지 않습니다. 목으로
 * 흉내 내면 <b>테스트가 프로덕션 코드의 호출 순서를 그대로 베낀 것</b>이 됩니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 작업 영속성 어댑터")
class AiTaskPersistenceAdapterTest {

    @Mock
    private JpaAiTaskRepository jpaRepository;
    @Mock
    private AiTaskMapper aiTaskMapper;
    @Mock
    private JPAQueryFactory queryFactory;

    @Mock
    private AiTaskEntity entity;

    @InjectMocks
    private AiTaskPersistenceAdapter adapter;

    private static final String TASK_ID = "task-1";
    private static final Long USER_ID = 7L;

    private static AiTask task(String id) {
        return new AiTask(
                id, USER_ID, "고양이", Proficiency.BEGINNER, TaskStatus.PENDING,
                null, null, null, LocalDateTime.now(), LocalDateTime.now(), null);
    }

    @Nested
    @DisplayName("저장 — id 유무로 신규와 갱신이 갈린다")
    class 저장 {

        /**
         * @implNote <b>동시 요청 차단의 마지막 방어선입니다.</b> 애플리케이션 계층은 스프링 예외를
         * 보지 않아야 하고({@code LayerDependencyTest.애플리케이션은_프레임워크_예외를_잡지_않는다}),
         * 그 번역이 일어나는 곳이 이 어댑터입니다.
         */
        @Test
        @DisplayName("유니크 제약 위반은 도메인 예외로 번역되어 나간다")
        void 유니크_제약_위반은_도메인_예외로_번역된다() {
            given(aiTaskMapper.toEntity(any(AiTask.class))).willReturn(entity);
            given(jpaRepository.save(entity))
                    .willThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "Duplicate entry for key 'uk_ai_task_active_user'"));

            assertThatThrownBy(() -> adapter.save(task(null)))
                    .isInstanceOf(AiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", AiErrorCode.DUPLICATE_AI_REQUEST);
        }

        @Test
        @DisplayName("신규 저장은 매퍼를 왕복해 도메인으로 돌아온다")
        void 신규_저장은_매퍼를_왕복한다() {
            AiTask expected = task(TASK_ID);

            given(aiTaskMapper.toEntity(any(AiTask.class))).willReturn(entity);
            given(jpaRepository.save(entity)).willReturn(entity);
            given(aiTaskMapper.toDomain(entity)).willReturn(expected);

            assertThat(adapter.save(task(null))).isSameAs(expected);
        }

        @Test
        @DisplayName("갱신 대상이 없으면 저장하지 않고 예외를 던진다")
        void 갱신_대상이_없으면_예외를_던진다() {
            given(jpaRepository.findById(TASK_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> adapter.save(task(TASK_ID)))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(TASK_ID);

            verify(jpaRepository, never()).save(any(AiTaskEntity.class));
        }

        /**
         * @implNote 갱신은 <b>새 엔티티를 만들지 않고</b> 조회한 엔티티에 도메인 값을 덮습니다.
         * {@code toEntity}로 새로 만들어 저장하면 영속성 컨텍스트가 관리하던 인스턴스와 분리되어
         * dirty checking이 어긋납니다.
         */
        @Test
        @DisplayName("갱신은 조회한 엔티티에 도메인을 덮어쓴다")
        void 갱신은_조회한_엔티티에_덮어쓴다() {
            AiTask domain = task(TASK_ID);
            AiTask expected = task(TASK_ID);

            given(jpaRepository.findById(TASK_ID)).willReturn(Optional.of(entity));
            given(jpaRepository.save(entity)).willReturn(entity);
            given(aiTaskMapper.toDomain(entity)).willReturn(expected);

            assertThat(adapter.save(domain)).isSameAs(expected);

            verify(aiTaskMapper).updateEntityFromDomain(domain, entity);
            verify(aiTaskMapper, never()).toEntity(any(AiTask.class));
        }
    }

    @Nested
    @DisplayName("조회")
    class 조회 {

        @Test
        @DisplayName("단건 조회는 매퍼를 거치고, 없으면 빈 Optional이다")
        void 단건_조회는_매퍼를_거친다() {
            AiTask expected = task(TASK_ID);
            given(jpaRepository.findById(TASK_ID)).willReturn(Optional.of(entity), Optional.empty());
            given(aiTaskMapper.toDomain(entity)).willReturn(expected);

            assertThat(adapter.findById(TASK_ID)).contains(expected);
            assertThat(adapter.findById(TASK_ID)).isEmpty();
        }

        @Test
        @DisplayName("비관적 락 조회도 같은 매퍼를 거친다")
        void 비관적_락_조회도_매퍼를_거친다() {
            AiTask expected = task(TASK_ID);
            given(jpaRepository.findByIdForUpdate(TASK_ID)).willReturn(Optional.of(entity));
            given(aiTaskMapper.toDomain(entity)).willReturn(expected);

            assertThat(adapter.findByIdForUpdate(TASK_ID)).contains(expected);
        }

        @Test
        @DisplayName("상태별 조회는 엔티티 목록을 도메인 목록으로 옮긴다")
        void 상태별_조회는_도메인_목록으로_옮긴다() {
            AiTask expected = task(TASK_ID);
            given(jpaRepository.findAllByStatus(TaskStatus.PROCESSING)).willReturn(List.of(entity));
            given(aiTaskMapper.toDomain(entity)).willReturn(expected);

            assertThat(adapter.findAllByStatus(TaskStatus.PROCESSING)).containsExactly(expected);
        }

        /**
         * @implNote 활성으로 보는 상태가 <b>둘</b>이라는 것이 정책입니다. {@code COMPLETED}나
         * {@code FAILED}가 목록에 섞이면 끝난 작업이 새 요청을 영원히 막습니다.
         */
        @Test
        @DisplayName("활성 작업 판단은 PENDING과 PROCESSING만 본다")
        void 활성_작업은_PENDING과_PROCESSING만_본다() {
            given(jpaRepository.existsByUserIdAndStatusIn(eq(USER_ID), anyList())).willReturn(true);

            assertThat(adapter.existsActiveTaskByUserId(USER_ID)).isTrue();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TaskStatus>> statuses = ArgumentCaptor.forClass(List.class);
            verify(jpaRepository).existsByUserIdAndStatusIn(eq(USER_ID), statuses.capture());

            assertThat(statuses.getValue())
                    .containsExactlyInAnyOrder(TaskStatus.PENDING, TaskStatus.PROCESSING);
        }
    }

    @Nested
    @DisplayName("벌크 실패 처리")
    class 벌크_실패_처리 {

        @Test
        @DisplayName("대상이 없으면 0을 반환하고 DB를 건드리지 않는다")
        void 대상이_없으면_DB를_건드리지_않는다() {
            assertThat(adapter.markFailedInBulk(List.of(), "timeout")).isZero();

            verifyNoInteractions(jpaRepository);
        }

        /**
         * @implNote 벌크 JPQL은 {@code @LastModifiedDate} 감사를 발동시키지 않으므로 어댑터가
         * {@code updatedAt}을 직접 채웁니다. 빠뜨리면 <b>다음 스윕이 같은 행을 또 집습니다.</b>
         */
        @Test
        @DisplayName("갱신 시각을 어댑터가 직접 채워 넘긴다")
        void 갱신_시각을_직접_채워_넘긴다() {
            LocalDateTime before = LocalDateTime.now();
            given(jpaRepository.markFailedInBulk(anyList(), anyString(), any(LocalDateTime.class)))
                    .willReturn(2);

            assertThat(adapter.markFailedInBulk(List.of("a", "b"), "timeout")).isEqualTo(2);

            ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(jpaRepository).markFailedInBulk(eq(List.of("a", "b")), eq("timeout"), now.capture());

            assertThat(now.getValue()).isNotNull().isAfterOrEqualTo(before);
        }
    }
}
