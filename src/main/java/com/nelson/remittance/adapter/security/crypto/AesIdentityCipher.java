package com.nelson.remittance.adapter.security.crypto;

import com.nelson.remittance.application.port.security.IdentityCipher;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 식별 번호를 무작위 nonce 기반 AES-GCM으로 암호화하는 저장 경계다.
// 필수 환경 키를 읽어 AES 키 길이를 시작 시점에 확인한다.
@Component
public final class AesIdentityCipher implements IdentityCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesIdentityCipher(@Value("${remittance.aes-key}") String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("REMITTANCE_AES_KEY must be Base64.");
        }
        if (bytes.length != 32) {
            throw new IllegalArgumentException("REMITTANCE_AES_KEY must contain 32 bytes.");
        }
        key = new SecretKeySpec(bytes, "AES");
    }

    @Override
    // 새 nonce와 인증 태그를 붙인 암호문을 Base64로 인코딩해 저장 형식으로 반환한다.
    public String encrypt(String value) {
        try {
            // 매번 다른 nonce와 GCM 인증 태그로 같은 번호도 다른 암호문을 만든다.
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD("identity:v1".getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder()
                    .encodeToString(
                            ByteBuffer.allocate(nonce.length + encrypted.length)
                                    .put(nonce)
                                    .put(encrypted)
                                    .array());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Identity encryption failed.");
        }
    }
}
