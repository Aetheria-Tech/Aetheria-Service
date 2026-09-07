package com.serverbe.adapter.in.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.serverbe.domain.exception.ErrorCode;
import com.serverbe.adapter.in.web.error.ErrorKindHttpStatusMapper;
import org.springframework.http.HttpStatus;

/**
 * @param success    API 호출 성공 여부
 * @param httpStatus HTTP 상태 코드 {@link HttpStatus}
 * @param data       성공 시 반환할 데이터 객체 (성공 시에만 포함)
 * @param error      실패 시 반환할 에러 상세 정보 {@link ApiError} (실패 시에만 포함)
 * @param <T>        반환할 데이터의 타입
 * @responsibility 시스템 전체에서 사용하는 <b>표준 REST API 응답 규격</b>을 정의합니다.
 */
public record RestApiResponse<T>(
        boolean success,

        HttpStatus httpStatus,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        T data,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        ApiError error
) {
    /**
     * @param data 클라이언트에 전달할 본문 데이터
     * @param <T>  데이터 타입
     * @return {@link HttpStatus#OK} 상태를 가진 {@link RestApiResponse} 인스턴스
     * @responsibility 성공 데이터가 포함된 <b>200 OK</b> 응답 객체를 생성합니다.
     */
    public static <T> RestApiResponse<T> success(T data) {
        return new RestApiResponse<>(true, HttpStatus.OK, data, null);
    }

    /**
     * @param data 클라이언트에 전달할 본문 데이터
     * @param <T>  데이터 타입
     * @return {@link HttpStatus#CREATED} 상태를 가진 {@link RestApiResponse} 인스턴스
     * @responsibility 성공 데이터가 포함된 <b>201 CREATED</b> 응답 객체를 생성합니다.
     */
    public static <T> RestApiResponse<T> created(T data) {
        return new RestApiResponse<>(true, HttpStatus.CREATED, data, null);
    }


    /**
     * @param errorMessage 서버에서 정의한 에러 메시지 정보
     * @return 에러 코드와 메시지가 포함된 응답 객체
     */
    public static RestApiResponse<Void> fail(ErrorCode errorMessage) {
        return new RestApiResponse<>(
                false,
                ErrorKindHttpStatusMapper.toHttpStatus(errorMessage.getKind()),
                null,
                new ApiError(errorMessage.getCode(), errorMessage.getMessage())
        );
    }

    /**
     * @param reason 클라이언트에게 전달할 구체적인 실패 사유
     * @return 상세 사유가 포함된 실패 응답 객체
     * @responsibility 기본 에러 정의 외에 <b>추가적인 상세 사유</b>를 포함하는 실패 응답 객체를 생성합니다.
     * @implNote 유효성 검사 실패 시 필드별 구체적인 오류 사유를 전달할 때 주로 사용합니다.
     */
    public static RestApiResponse<Void> fail(ErrorCode errorMessage, String reason) {
        return new RestApiResponse<>(
                false,
                ErrorKindHttpStatusMapper.toHttpStatus(errorMessage.getKind()),
                null,
                new ApiError(errorMessage.getCode(), reason)
        );
    }

    /**
     * @return {@link HttpStatus#NO_CONTENT} 상태를 가진 응답 객체
     * @responsibility 데이터 반환이 없는 성공 응답(204 No Content) 객체를 생성합니다.
     */
    public static RestApiResponse<Void> noContent() {
        return new RestApiResponse<>(
                true,
                HttpStatus.NO_CONTENT,
                null,
                null
        );
    }

    /**
     * @param code    서버 정의 비즈니스 에러 코드
     * @param message 에러에 대한 설명 또는 사유
     * @responsibility 실패 응답 시 클라이언트가 인지할 수 있는 <b>에러 상세 정보</b>를 담는 내부 객체입니다.
     */
    public record ApiError(
            String code,
            String message
    ) {
    }
}