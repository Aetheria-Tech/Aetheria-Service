package com.serverbe.adapter.out.crypto;

import com.serverbe.infrastructure.config.properties.EncryptionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AesGcmEncryptorTest {
    @Mock
    private EncryptionProperties properties;

    // 실제 IV(초기화 벡터)를 생성하기 위해 필요합니다. 이 필드가 없으면 @InjectMocks가 null을 주입하여
    // generateIv() 호출 시 NPE가 발생하고, encrypt()가 이를 삼켜 CryptoException으로 감춰버립니다.
    @Mock
    private SecureRandom secureRandom;

    @InjectMocks
    private AesGcmEncryptor encryptor;

    private final String v1Key = "test-key-v1-do-not-use-in-prod!!";
    private final String v2Key = "test-key-v2-do-not-use-in-prod!!";
    private final String plainText = "Hello, World! 12345";

    @BeforeEach
    void setUp() {
        // 기본 설정: v2를 활성 버전으로 설정
        lenient().when(properties.algorithm()).thenReturn("AES/GCM/NoPadding");
        lenient().when(properties.keyAlgorithm()).thenReturn("AES");
        lenient().when(properties.tagLengthBit()).thenReturn(128);
        lenient().when(properties.ivLengthByte()).thenReturn(12);
    }

    @Test
    @DisplayName("성공: 현재 활성화된 키(v2)로 암호화하고 복호화하면 원문이 나와야 한다.")
    void encryptAndDecryptSuccess() {
        // given
        when(properties.activeVersion()).thenReturn("v2");
        when(properties.getActiveKey()).thenReturn(v2Key.getBytes(StandardCharsets.UTF_8));
        when(properties.getKeyByVersion("v2")).thenReturn(v2Key.getBytes(StandardCharsets.UTF_8));

        // when
        String encrypted = encryptor.encrypt(plainText);
        String decrypted = encryptor.decrypt(encrypted);

        // then
        assertThat(encrypted).startsWith("v2:");
        assertThat(decrypted).isEqualTo(plainText);
    }

    @Test
    @DisplayName("성공: v1으로 암호화된 데이터를 활성 버전이 v2인 상태에서도 복호화할 수 있어야 한다.")
    void decryptOldVersionWithNewActiveVersion() {
        // given: 먼저 v1으로 암호화된 데이터 준비
        when(properties.activeVersion()).thenReturn("v1");
        when(properties.getActiveKey()).thenReturn(v1Key.getBytes(StandardCharsets.UTF_8));
        String encryptedWithV1 = encryptor.encrypt(plainText);

        // when: 시스템의 활성 버전이 v2로 변경됨
        when(properties.getKeyByVersion("v1")).thenReturn(v1Key.getBytes(StandardCharsets.UTF_8));

        String decrypted = encryptor.decrypt(encryptedWithV1);

        // then
        assertThat(decrypted).isEqualTo(plainText);
    }

    @Test
    @DisplayName("실패: 존재하지 않는 버전의 암호문은 복호화 시 예외가 발생해야 한다.")
    void decryptWithInvalidVersion() {
        // given
        String invalidEncrypted = "v99:iv:cipher";
        when(properties.getKeyByVersion("v99")).thenThrow(new IllegalArgumentException("지원되지 않는 버전"));

        // when & then
        assertThatThrownBy(() -> encryptor.decrypt(invalidEncrypted))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("복호화 실패");
    }

    @Test
    @DisplayName("성공: 빈 문자열이나 null이 들어오면 null을 반환해야 한다.")
    void handleNullInput() {
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
    }
}