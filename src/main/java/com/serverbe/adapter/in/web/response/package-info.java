/**
 * <h2>Incoming Web Response Envelope</h2>
 * <p>
 * 모든 HTTP 응답이 입는 봉투 {@code RestApiResponse}(+중첩 {@code ApiError})가 있습니다.
 * {@code HttpStatus}를 필드로 들고 {@code @JsonInclude}로 직렬화 모양을 정하므로
 * <b>HTTP와 JSON 둘 다에 묶여 있고</b>, 그래서 자리는 인프라가 아니라 웹 어댑터입니다.
 * 이 봉투가 {@code infrastructure}에 있는 동안 컨트롤러 여섯 개가 오직 이 한 타입 때문에
 * 인프라를 import했습니다.
 * </p>
 * <p>
 * 규칙으로 남기지 못하는 문장이 하나 있습니다 — <b>{@code HttpStatus}를 필드로 갖는 타입은 웹
 * 어댑터에 있어야 합니다.</b> 애노테이션이 없는 타입이라 {@code LayerDependencyTest}의
 * {@code 웹_애노테이션이_붙은_클래스는_웹_어댑터다}가 잡지 못합니다. 규칙이 좁혀 준 뒤 남는
 * 잔여물이고, 이 문장이 규칙을 대신합니다.
 * </p>
 * <p>
 * <b>필드명은 클라이언트와의 계약입니다.</b> {@code success}·{@code httpStatus}·{@code data}·
 * {@code error} 구조와 {@code httpStatus}가 숫자가 아니라 enum 이름으로 나간다는 사실을
 * {@code RestApiResponseJsonContractTest}가 문자열로 고정하고 있습니다. 봉투의 모양을 바꾸려면
 * 그 테스트를 먼저 고쳐야 하고, 그것은 클라이언트 호환성 판단이 필요한 별개의 일입니다.
 * </p>
 * <p>
 * <b>여기를 인프라가 거꾸로 참조합니다.</b> {@code infrastructure.security}의
 * {@code CustomAuthenticationEntryPoint}·{@code CustomAccessDeniedHandler}가 이 봉투를 씁니다.
 * 둘은 스프링 MVC 밖(필터 체인)에서 {@code HttpServletResponse}에 직접 JSON을 쓰는 시큐리티 훅이라
 * {@code BusinessExceptionHandler}가 잡지 못하고, 포트 구현이 아니라 프레임워크 배선이라
 * 인프라에 남았습니다. 그 결과 {@code infrastructure → adapter.in.web} 방향이 생겼습니다.
 * 막는 ArchUnit 규칙이 없어 조용히 통과하므로 <b>여기에 적어 둡니다</b> — 의도된 예외이고,
 * 두 훅의 자리는 별도 항목으로 다시 판단합니다.
 * </p>
 */
package com.serverbe.adapter.in.web.response;
