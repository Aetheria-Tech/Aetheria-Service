package com.serverbe.adapter.out.crypto;

import com.serverbe.application.port.out.crypto.EncryptPort;
import com.serverbe.domain.exception.crypto.CryptoErrorCode;
import com.serverbe.domain.exception.crypto.CryptoException;
import com.serverbe.infrastructure.config.properties.EncryptionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * @author Duskafka
 * @responsibility AES-GCM 알고리즘을 사용하여 데이터의 기밀성과 무결성을 보장하는 암호화 기능을 수행합니다.
 * 암호문 내에 버전을 명시하여 키 로테이션(Key Rotation) 대응이 가능한 구조를 제공합니다.
 * @implSpec {@link EncryptPort}를 구현하며, Java Cryptography Architecture(JCA)의 {@link Cipher}와
 * {@link EncryptionProperties}에 정의된 정책에 따라 동작합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AesGcmEncryptor implements EncryptPort {
    private final EncryptionProperties properties;
    private final SecureRandom secureRandom;
    private static final String DELIMITER = ":";

    /**
     * @param plainText 암호화할 원본 문자열
     * @return 버전 정보와 IV가 포함된 최종 암호화 문자열
     * @responsibility 평문을 현재 활성화된 최신 키 버전으로 암호화합니다.
     * @implSpec 1. 매 암호화마다 {@link #generateIv()}를 통해 고유한 IV를 생성합니다.<br>
     * 2. AES-GCM 알고리즘과 {@link EncryptionProperties#getActiveKey()}를 사용하여 암호화를 수행합니다.<br>
     * 3. 결과물은 {@code 버전:Base64(IV):Base64(암호문)} 형태로 결합하여 반환합니다.
     * @implNote 입력값이 {@code null}일 경우 보안 정책에 따라 {@code null}을 즉시 반환합니다.
     */
    @Override
    public String encrypt(String plainText) {
        if (plainText == null) return null;

        try {
            byte[] iv = generateIv();
            Cipher cipher = Cipher.getInstance(properties.algorithm());
            GCMParameterSpec spec = new GCMParameterSpec(properties.tagLengthBit(), iv);
            SecretKeySpec keySpec = new SecretKeySpec(properties.getActiveKey(), properties.keyAlgorithm());

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 최종 포맷: v1:Base64(IV):Base64(Cipher)
            return String.join(DELIMITER,
                    properties.activeVersion(),
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(cipherText)
            );
        } catch (Exception e) {
            log.error("Failed to encrypt data", e);
            throw new CryptoException(CryptoErrorCode.ENCRYPTION_FAILURE);
        }
    }

    /**
     * @param encryptedData 복호화할 암호화 문자열 (형식: {@code version:iv:cipherText})
     * @return 복호화된 원본 평문 문자열
     * @responsibility 암호문에 포함된 버전 정보를 해석하여 적절한 키로 복호화를 수행합니다.
     * @implSpec 1. 구분자({@code :})를 기준으로 암호문을 분리하여 버전, IV, 암호문 데이터를 추출합니다.<br>
     * 2. 추출된 버전에 해당하는 복호화 키를 {@link EncryptionProperties#getKeyByVersion(String)}에서 조회합니다.<br>
     * 3. GCM 모드의 인증 태그(Auth Tag) 검증을 포함하여 복호화를 진행합니다.
     * @implNote 암호문 형식이 올바르지 않거나 버전 정보가 시스템에 존재하지 않는 경우 {@link CryptoException}이 발생합니다.
     */
    @Override
    public String decrypt(String encryptedData) {
        if (encryptedData == null) return null;

        try {
            String[] parts = encryptedData.split(DELIMITER);
            if (parts.length != 3) {
                throw new CryptoException(CryptoErrorCode.INCORRECT_CIPHERTEXT_FORMAT);
            }

            String version = parts[0];
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] cipherText = Base64.getDecoder().decode(parts[2]);
            byte[] key = properties.getKeyByVersion(version);

            Cipher cipher = Cipher.getInstance(properties.algorithm());
            GCMParameterSpec spec = new GCMParameterSpec(properties.tagLengthBit(), iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, properties.keyAlgorithm());

            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt data", e);
            throw new CryptoException(CryptoErrorCode.DECRYPTION_FAILED);
        }
    }

    /**
     * @return 생성된 임의의 IV 바이트 배열
     * @responsibility 암호화의 무결성을 위해 예측 불가능한 초기화 벡터(IV)를 생성합니다.
     * @implSpec {@link SecureRandom}을 사용하여 {@link EncryptionProperties#ivLengthByte()}에 정의된 길이만큼 난수 바이트를 생성합니다.
     */
    private byte[] generateIv() {
        byte[] iv = new byte[properties.ivLengthByte()];
        secureRandom.nextBytes(iv);
        return iv;
    }

    /**
     * @param cipherText 확인할 암호화 문자열
     * @return 최신 버전이면 true, 구버전이거나 형식이 다르면 false
     * @responsibility 주어진 암호문이 시스템의 최신 암호화 버전으로 생성되었는지 확인합니다.
     * @implNote 이 메서드는 데이터 마이그레이션(Re-encryption) 대상을 판별하는 지표로 활용됩니다.
     */
    @Override
    public boolean isLatestVersion(String cipherText) {
        if (cipherText == null) return false;
        return cipherText.startsWith(properties.activeVersion() + DELIMITER);
    }
}