package com.serverbe.domain.exception;

import com.serverbe.domain.exception.ai.AiErrorCode;
import com.serverbe.domain.exception.art.ArtErrorCode;
import com.serverbe.domain.exception.auth.AuthErrorCode;
import com.serverbe.domain.exception.crypto.CryptoErrorCode;
import com.serverbe.domain.exception.external.ExternalApiErrorCode;
import com.serverbe.domain.exception.s3.S3ErrorCode;
import com.serverbe.domain.exception.sagemaker.SageMakerErrorCode;
import com.serverbe.domain.exception.server.ServerErrorCode;
import com.serverbe.domain.exception.user.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @responsibility 에러 코드 enum 전체를 한 번에 훑어 계약이 깨지지 않았는지 확인합니다.
 * @implNote HttpStatus를 ErrorKind로 옮길 때 상수 50개를 손으로 고쳤습니다. 하나를 빠뜨리거나
 * 코드 문자열을 중복시키는 사고는 눈으로 잡히지 않으므로 테스트가 대신 봅니다.
 */
@DisplayName("에러 코드 enum 계약")
class ErrorCodeContractTest {

    private static final List<Class<? extends ErrorCode>> ERROR_CODE_ENUMS = List.of(
            AiErrorCode.class, ArtErrorCode.class, AuthErrorCode.class, CryptoErrorCode.class,
            ExternalApiErrorCode.class, S3ErrorCode.class, SageMakerErrorCode.class,
            ServerErrorCode.class, UserErrorCode.class
    );

    private static List<ErrorCode> allConstants() {
        return ERROR_CODE_ENUMS.stream()
                .flatMap(type -> Arrays.stream(type.getEnumConstants()))
                .map(ErrorCode.class::cast)
                .toList();
    }

    @Test
    @DisplayName("모든 상수가 종류·코드·메시지를 빠짐없이 가진다")
    void 모든_상수가_종류_코드_메시지를_빠짐없이_가진다() {
        assertThat(allConstants()).allSatisfy(errorCode -> {
            assertThat(errorCode.getKind()).as("%s 의 종류", errorCode).isNotNull();
            assertThat(errorCode.getCode()).as("%s 의 코드", errorCode).isNotBlank();
            assertThat(errorCode.getMessage()).as("%s 의 메시지", errorCode).isNotBlank();
        });
    }

    @Test
    @DisplayName("에러 코드 문자열은 전체에서 유일하다")
    void 에러_코드_문자열은_전체에서_유일하다() {
        assertThat(allConstants()).extracting(ErrorCode::getCode).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("에러 코드 상수는 50개다")
    void 에러_코드_상수는_50개다() {
        assertThat(allConstants()).hasSize(50);
    }
}
