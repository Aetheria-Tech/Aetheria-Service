/**
 * <h2>Cross-Cutting Concerns</h2>
 * <p>
 * 어느 계층에도 속하지 않고 <b>전 계층을 가로지르는</b> 기술 관심사가 모입니다.
 * 현재는 {@code logging}의 {@code @Trace}·{@code @Timer} AOP뿐입니다.
 * </p>
 * <p>
 * <b>표준 응답 규격은 여기 없습니다.</b> {@code RestApiResponse}는 {@code HttpStatus}를 필드로 갖는
 * HTTP 응답 봉투라 횡단 관심사가 아니라 웹 어댑터의 것이고,
 * {@code adapter.in.web.response}로 옮겼습니다. 같은 이유로 {@code @RestControllerAdvice}였던
 * {@code BusinessExceptionHandler}도 {@code adapter.in.web.error}에 있습니다.
 * </p>
 */
package com.serverbe.infrastructure.common;
