/**
 * <h2>Application Ports</h2>
 * <p>
 * <b>In Port:</b> 외부(Web)가 내부(Service)를 호출하는 인터페이스 (UseCase) <br>
 * <b>Out Port:</b> 내부(Service)가 외부(DB, API)를 호출하는 인터페이스
 * </p>
 *
 * <h3>하나의 포트인가, 둘인가</h3>
 * <p>
 * <b>메서드 개수는 기준이 아닙니다.</b> 옳은 숫자가 없고, 숫자로 잡으면 옳은 코드를 막습니다
 * (같은 이유로 {@code application/service/package-info.java}도 유스케이스 개수를 세지 않습니다).
 * 대신 이렇게 가릅니다.
 * </p>
 * <blockquote>
 * <b>한 포트의 메서드들이 같은 키 모델을 공유하지 않으면 두 포트입니다.</b> 무엇으로 찾는가가
 * 갈리는 순간, 그 둘은 같은 저장소를 쓸 뿐 같은 개념이 아닙니다.
 * </blockquote>
 * <p>
 * {@code TokenPersistencePort}가 이 기준으로 갈라졌습니다. 세션 관리는 {@code userId + deviceId}로
 * 찾아 키의 주인이 <b>사용자</b>였고, 블랙리스트는 토큰 문자열로 찾아 키의 주인이 <b>토큰</b>이었으며
 * 사용자를 알지도 못했습니다. 같은 Redis를 쓴다는 것 말고는 데이터 모델도 수명 정책도 공유하는 것이
 * 없었습니다. 근거는 {@code docs/refactor/09-fat-port-token-persistence.md}에 있습니다.
 * </p>
 * <p>
 * <b>두 포트를 함께 주입받는 호출자가 생기는 것은 실패가 아닙니다.</b> 로그아웃처럼 실제로 두 갈래를
 * 함께 쓰는 유스케이스가 있고, 넓은 포트 하나는 그 지점을 드러내는 대신 감춥니다.
 * </p>
 */
package com.serverbe.application.port;