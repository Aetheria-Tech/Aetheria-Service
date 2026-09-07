/**
 * <h2>Outgoing Security Adapters</h2>
 * <p>
 * {@code application.port.out.security}의 구현체가 위치합니다. 애플리케이션은
 * {@code TokenProvider}·{@code TokenResolver}를 부를 뿐 그것이 JWT인지 불투명 토큰인지
 * 알지 못합니다. <b>교체 가능성이 설계에 이미 들어 있으므로 위치도 그것을 말해야 합니다.</b>
 * </p>
 * <p>
 * {@code JwtKeyManager}는 포트 구현체가 아니지만 여기 함께 있습니다. 서명 키와 파서를 쥔
 * <b>JWT 전용 부품</b>이고 사용처가 위 두 어댑터뿐이라, 토큰 방식을 바꾸면 두 어댑터와 함께
 * 사라집니다. 즉 접착제가 아니라 교체 대상 쪽입니다.
 * </p>
 * <p>
 * 반면 {@code SecurityConfig}(필터 체인 구성), {@code TokenExtractor}(HTTP 요청에서 토큰 추출),
 * 인증 실패 훅들은 <b>인프라에 남았습니다.</b> 포트 구현이 아니라 프레임워크 배선이기 때문입니다.
 * </p>
 */
package com.serverbe.adapter.out.security;
