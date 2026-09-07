/**
 * <h2>Application Services</h2>
 * <p>
 * 도메인 로직을 조합하여 비즈니스 유스케이스를 구현합니다.
 * </p>
 *
 * <b>더티 체킹 및 트랜잭션:</b>
 * <ul>
 * <li>{@code @Transactional}을 통해 트랜잭션 경계를 설정합니다.</li>
 * <li><b>더티 체킹의 어려움:</b> 이 서비스는 JPA 엔티티가 아닌 '순수 도메인 모델'을 다룹니다.
 * 따라서 더티 체킹에 의존하기보다, 수정된 도메인 모델을 Persistence Port로 넘겨 명시적으로 저장하는 방식을 주로 사용합니다.</li>
 * </ul>
 *
 * <b>한 서비스가 유스케이스를 몇 개까지 구현해도 되는가:</b>
 * <p>
 * <b>옳은 숫자는 없습니다.</b> 여럿을 구현해도 좋습니다 — <b>협력자와 실행 모델을 공유할 때만.</b>
 * 주입받는 포트가 갈라지거나, 한쪽만 리액티브거나, 호출자가 사용자와 내부 흐름으로 나뉘면
 * 그건 두 서비스입니다.
 * </p>
 * <p>
 * 그래서 {@code UserService}(조회·수정)와 {@code RunningArtService}(조회·수정·삭제)는 여럿을
 * 구현한 채 둡니다. 전자는 같은 대상을 다루고, 후자는 셋 다 {@code findAndVerifyOwner}를 거칩니다 —
 * <b>검증 로직을 공유하는 것이 응집의 근거입니다.</b> 반면 위치 기반 탐색과 AI 결과 등록은
 * {@code RunningArtSearchService}·{@code RunningArtRegistrationService}로 갈라져 나왔습니다.
 * 개수가 아니라 성격이 달랐기 때문입니다.
 * </p>
 * <p>
 * 이 기준을 ArchUnit 규칙으로 만들지 않는 이유도 같습니다. <b>숫자로 잡으면 옳은 코드를 막습니다.</b>
 * </p>
 */
package com.serverbe.application.service;