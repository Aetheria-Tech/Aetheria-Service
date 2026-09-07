/**
 * <h2>Incoming Web Error Adapters</h2>
 * <p>
 * {@code @RestControllerAdvice}인 {@code BusinessExceptionHandler}가 있습니다.
 * <b>이것은 컨트롤러의 일부입니다.</b> 스프링 MVC가 컨트롤러에서 던져진 예외를 가로채 응답 본문과
 * 상태 코드를 만드는 자리이고, 컨트롤러가 정상 경로에서 하는 일을 예외 경로에서 그대로 합니다.
 * 컨트롤러가 {@code adapter.in.web}에 있으므로 이것도 여기 있습니다.
 * </p>
 * <p>
 * {@code ErrorKindHttpStatusMapper}는 애노테이션이 붙은 어댑터가 아니지만 함께 있습니다.
 * 도메인의 {@code ErrorKind}가 {@code HttpStatus}를 만나는 <b>저장소 전체에서 유일한 지점</b>이고,
 * 쓰는 곳이 위 핸들러와 {@code adapter.in.web.response.RestApiResponse} 둘뿐입니다. 웹 프로토콜을
 * 바꾸면 이 둘과 함께 사라지므로 접착제가 아니라 교체 대상 쪽입니다.
 * </p>
 * <p>
 * 이 매핑을 <b>도메인으로 되돌리지 않습니다.</b> 도메인에서 {@code HttpStatus}를 걷어낸 것이
 * 커밋 {@code 0254366}의 결론이고, {@code LayerDependencyTest}의
 * {@code 도메인은_JDK와_Lombok에만_의존한다}가 그 결론을 지키고 있습니다. HTTP는 여기까지만
 * 올라옵니다.
 * </p>
 * <p>
 * 인증·인가 실패를 필터 체인에서 처리하는 {@code CustomAuthenticationEntryPoint}·
 * {@code CustomAccessDeniedHandler}는 <b>여기 없습니다.</b> 스프링 MVC 밖에서 동작해
 * {@code @ExceptionHandler}가 도달하지 못하는 시큐리티 훅이라, 포트 구현이 아닌 프레임워크
 * 배선으로 보아 {@code infrastructure.security}에 남았습니다.
 * </p>
 */
package com.serverbe.adapter.in.web.error;
