package com.serverbe.adapter.in.web.error;

import com.serverbe.domain.exception.BusinessException;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.server.AsyncRaceConditionException;
import com.serverbe.domain.exception.server.DataIntegrityViolationException;
import com.serverbe.domain.exception.server.RateLimitExceededException;
import com.serverbe.domain.exception.server.ServerErrorCode;
import com.serverbe.adapter.in.web.response.RestApiResponse;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Duskafka
 * @responsibility 애플리케이션 전역에서 발생하는 예외를 가로채어 공통 응답 규격({@link RestApiResponse})으로 변환하는 통합 에러 핸들러입니다.
 * @implSpec 1. <b>Domain Decoupling</b>: 도메인 레이어에서 발생한 {@link BusinessException}을 HTTP 상태 코드와 매핑합니다.<br>
 * 2. <b>Validation Translation</b>: 프레임워크 수준의 검증 오류(Binding, Constraint)를 클라이언트가 이해하기 쉬운 상세 메시지로 가공합니다.<br>
 * 3. <b>Security Bridging</b>: Spring Security 내부 예외를 도메인 에러 코드 체계에 통합합니다.
 */
@Slf4j
@RestControllerAdvice
public class BusinessExceptionHandler {

    /**
     * @param e 도메인 비즈니스 로직 위반 예외
     * @responsibility 정의된 비즈니스 규칙 위반 시 해당 에러 코드의 상태값과 메시지를 응답합니다.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<RestApiResponse<Void>> handleBusinessException(BusinessException e) {
        var errorCode = e.getErrorCode();
        log.warn("[BUSINESS EXCEPTION] 비즈니스 로직 위반 발생 -> {}", errorCode.getMessage());

        return ResponseEntity.status(ErrorKindHttpStatusMapper.toHttpStatus(errorCode.getKind()))
                .body(RestApiResponse.fail(errorCode, errorCode.getMessage()));
    }

    /**
     * @param e DTO 필드 유효성 검증 실패 예외
     * @responsibility <b>@Valid</b> 검증 실패 시 발생하며, 어떤 필드에서 어떤 사유로 실패했는지 상세 내용을 수집합니다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<RestApiResponse<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String detailedErrorMessage = e.getBindingResult().getFieldErrors().stream()
                .map(error -> String.format("[%s]: %s", error.getField(), error.getDefaultMessage()))
                .collect(Collectors.joining(", "));

        String globalErrorMessage = e.getBindingResult().getGlobalErrors().stream()
                .map(error -> String.format("[%s]: %s", error.getObjectName(), error.getDefaultMessage()))
                .collect(Collectors.joining(", "));

        String combinedMessage = Stream.of(detailedErrorMessage, globalErrorMessage)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("; "));

        String finalMessage = combinedMessage.isEmpty()
                ? ServerErrorCode.INVALID_REQUEST_PARAMETER.getMessage()
                : combinedMessage;

        log.warn("[VALIDATION ERROR] 요청 파라미터 유효성 검증 실패 -> {}", finalMessage);

        return ResponseEntity.status(ErrorKindHttpStatusMapper.toHttpStatus(ServerErrorCode.INVALID_REQUEST_PARAMETER.getKind()))
                .body(RestApiResponse.fail(ServerErrorCode.INVALID_REQUEST_PARAMETER, finalMessage));
    }

    /**
     * @param e 제약 조건 위반 예외 (주로 @Validated 파라미터)
     * @responsibility 메서드 파라미터나 경로 변수(@PathVariable)의 유효성 검증 실패 시 상세 정보를 제공합니다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<RestApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException e) {
        String detailedErrorMessage = e.getConstraintViolations().stream()
                .map(violation -> {
                    var pathIterator = violation.getPropertyPath().iterator();
                    String parameterName = "";
                    while (pathIterator.hasNext()) {
                        parameterName = pathIterator.next().getName();
                    }
                    return String.format("[%s]: %s", parameterName, violation.getMessage());
                })
                .collect(Collectors.joining(", "));

        log.warn("[CONSTRAINT ERROR] 제약 조건 위반 발생 -> {}", detailedErrorMessage);

        return ResponseEntity.status(ErrorKindHttpStatusMapper.toHttpStatus(ServerErrorCode.INVALID_REQUEST_PARAMETER.getKind()))
                .body(RestApiResponse.fail(ServerErrorCode.INVALID_REQUEST_PARAMETER, detailedErrorMessage));
    }

    /**
     * @responsibility 필수 파라미터 누락 시 400 에러를 반환합니다.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<RestApiResponse<Void>> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        String detailedErrorMessage = String.format("필수 파라미터 [%s](이)가 누락되었습니다.", e.getParameterName());
        log.warn("[PARAMETER MISSING] 필수 요청 파라미터 누락 -> {}", detailedErrorMessage);

        return ResponseEntity.status(ErrorKindHttpStatusMapper.toHttpStatus(ServerErrorCode.INVALID_REQUEST_PARAMETER.getKind()))
                .body(RestApiResponse.fail(ServerErrorCode.INVALID_REQUEST_PARAMETER, detailedErrorMessage));
    }

    /**
     * @responsibility JSON 역직렬화 실패(형식 오류) 시 400 에러를 반환합니다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<RestApiResponse<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("[JSON PARSE ERROR] 잘못된 형식의 JSON 요청 수신 -> {}", e.getMostSpecificCause().getMessage());

        return ResponseEntity.status(ErrorKindHttpStatusMapper.toHttpStatus(ServerErrorCode.MALFORMED_JSON_REQUEST.getKind()))
                .body(RestApiResponse.fail(ServerErrorCode.MALFORMED_JSON_REQUEST));
    }

    /**
     * @responsibility 미처 파악하지 못한 시스템 전역 예외를 처리하는 최후의 보루입니다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<RestApiResponse<Void>> handleException(Exception e) {
        log.error("[UNHANDLED INTERNAL ERROR] 처리되지 않은 시스템 예외 발생", e);

        return ResponseEntity.status(ErrorKindHttpStatusMapper.toHttpStatus(ServerErrorCode.INTERNAL_SERVER_ERROR.getKind()))
                .body(RestApiResponse.fail(ServerErrorCode.INTERNAL_SERVER_ERROR));
    }

    /**
     * @responsibility 인증 실패(401)에 대한 보안 예외를 처리합니다.
     */
    @ExceptionHandler({AuthenticationException.class, InsufficientAuthenticationException.class})
    public ResponseEntity<RestApiResponse<Void>> handleAuthenticationException(Exception e) {
        log.warn("[SECURITY UNAUTHORIZED] 미인증 사용자의 접근 시도 -> {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(RestApiResponse.fail(AuthErrorCode.UNAUTHORIZED));
    }

    /**
     * @responsibility 권한 부족(403)에 대한 보안 예외를 처리합니다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<RestApiResponse<Void>> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("[SECURITY ACCESS DENIED] 권한 없는 사용자의 자원 접근 시도 -> {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(RestApiResponse.fail(AuthErrorCode.ACCESS_DENIED));
    }

    /**
     * @param e JPA 영속성 컨텍스트 예외
     * @responsibility 어댑터 계층(DB)에서 데이터 정합성이 깨지거나 동시성 문제가 발생했을 때 500 에러로 처리합니다.
     * @implNote 도메인 계층의 404(Not Found) 에러와 달리, 이 예외는 서버 내부의 인프라적 장애를 의미합니다.
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<RestApiResponse<Void>> handleEntityNotFoundException(EntityNotFoundException e) {
        // 일반적인 조회 실패는 서버 에러가 아니므로 warn 로그로 남깁니다.
        log.warn("[CLIENT ERROR] 요청한 리소스를 찾을 수 없습니다 -> {}", e.getMessage());

        // 404 상태 코드와 그에 맞는 에러 코드를 반환하도록 수정
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(RestApiResponse.fail(ServerErrorCode.RESOURCE_NOT_FOUND));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<RestApiResponse<Void>> handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        log.error("[INFRA EXCEPTION] DB 데이터 정합성 오류 (동시성/삭제 문제 예상) -> {}", e.getMessage());

        return ResponseEntity.status(ErrorKindHttpStatusMapper.toHttpStatus(ServerErrorCode.INTERNAL_SERVER_ERROR.getKind()))
                .body(RestApiResponse.fail(ServerErrorCode.INTERNAL_SERVER_ERROR));
    }

    /**
     * @param e 처리율 제한 초과 예외
     * @responsibility 429 에러 응답 시, HTTP 표준인 'Retry-After' 헤더에 재시도 가능 시간을 명시합니다.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<RestApiResponse<Void>> handleRateLimitExceededException(RateLimitExceededException e) {
        var errorCode = e.getErrorCode();
        log.warn("[RATE LIMIT EXCEPTION] 요청 한도 초과 -> {} (Retry-After: {}초)",
                errorCode.getMessage(), e.getRetryAfterSeconds());

        return ResponseEntity.status(ErrorKindHttpStatusMapper.toHttpStatus(errorCode.getKind()))
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(RestApiResponse.fail(errorCode, errorCode.getMessage()));
    }

    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbortException(ClientAbortException e) {
        log.warn("클라이언트 연결 중단: {}", e.getMessage());
        // 별도의 응답 객체를 반환하지 않음
    }

    /**
     * @param e 비동기 경합 조건 예외
     * @responsibility HTTP 클라이언트 요청 처리 중 상태 업데이트 경합(Race Condition)이 발생했을 때 409 Conflict 에러를 반환합니다.
     * @implNote SQS 리스너 등 백그라운드 워커에서 발생한 예외는 이 핸들러를 타지 않고 프레임워크 레벨에서 즉시 재시도(Retry) 처리됩니다.
     */
    @ExceptionHandler(AsyncRaceConditionException.class)
    public ResponseEntity<RestApiResponse<Void>> handleAsyncRaceConditionException(AsyncRaceConditionException e) {
        var errorCode = e.getErrorCode();
        log.warn("[RACE CONDITION] 상태 업데이트 경합 발생 (API 요청) -> {}", errorCode.getMessage());

        return ResponseEntity.status(ErrorKindHttpStatusMapper.toHttpStatus(errorCode.getKind()))
                .body(RestApiResponse.fail(errorCode, errorCode.getMessage()));
    }
}