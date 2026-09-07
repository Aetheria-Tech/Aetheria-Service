package com.serverbe.adapter.in.web.support;

import com.serverbe.application.port.dto.PageQuery;
import com.serverbe.application.port.dto.PageResult;
import com.serverbe.domain.exception.server.ServerErrorCode;
import com.serverbe.domain.exception.server.ServerException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * @responsibility 웹 계층의 Spring Data 페이징 타입과 애플리케이션의 페이징 타입을 서로 옮깁니다.
 * @implSpec 요청 쪽은 {@link Pageable} → {@link PageQuery}, 응답 쪽은 {@link PageResult} →
 * {@link Page}입니다. 응답을 다시 {@link PageImpl}로 감싸는 이유는 <b>JSON 계약을 바꾸지 않기</b>
 * 위해서입니다. 현재 목록 응답은 {@code content}, {@code pageable}, {@code sort} 등 최상위 키 11개를
 * 가진 Spring Data 기본 직렬화 형태이고, 자체 DTO로 바꾸면 클라이언트가 깨집니다.
 * @implNote {@link Pageable} 자체는 스프링 MVC의 인자 해석 기능이라 컨트롤러에 두는 것이 맞습니다.
 * 걷어내야 할 것은 <b>포트 시그니처</b>에 드러난 저장소 기술이지, 웹 어댑터가 쓰는 웹 기술이 아닙니다.
 */
public final class PageQueryMapper {

    private PageQueryMapper() {
        throw new ServerException(ServerErrorCode.UTILITY_CLASS);
    }

    /**
     * @param pageable 스프링 MVC가 해석한 페이징 요청
     * @return 애플리케이션 계층이 이해하는 페이징 요청
     */
    public static PageQuery toPageQuery(Pageable pageable) {
        List<PageQuery.SortOrder> sorts = pageable.getSort().stream()
                .map(order -> new PageQuery.SortOrder(
                        order.getProperty(),
                        order.getDirection() == Sort.Direction.ASC
                                ? PageQuery.Direction.ASC
                                : PageQuery.Direction.DESC))
                .toList();

        return new PageQuery(pageable.getPageNumber(), pageable.getPageSize(), sorts);
    }

    /**
     * @param result   애플리케이션 계층이 돌려준 페이지
     * @param pageable 원래 요청. 정렬 정보를 응답 JSON에 그대로 싣기 위해 필요하다.
     * @return 기존과 동일한 JSON으로 직렬화되는 {@link Page}
     */
    public static <T> Page<T> toPage(PageResult<T> result, Pageable pageable) {
        return new PageImpl<>(result.content(), pageable, result.totalElements());
    }
}
