package com.serverbe.domain.exception.crypto;

import com.serverbe.domain.exception.ErrorCode;
import com.serverbe.domain.exception.ErrorKind;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CryptoErrorCode implements ErrorCode {
    INCORRECT_CIPHERTEXT_FORMAT(ErrorKind.INTERNAL_ERROR, "CRYPTO_001", "잘못된 암호화 포멧입니다."),
    DECRYPTION_FAILED(ErrorKind.INTERNAL_ERROR, "CRYPTO_002", "복호화 실패"),
    ENCRYPTION_FAILURE(ErrorKind.INTERNAL_ERROR, "CRYPTO_003", "암호화 실패");

    private final ErrorKind kind;
    private final String code;
    private final String message;
}