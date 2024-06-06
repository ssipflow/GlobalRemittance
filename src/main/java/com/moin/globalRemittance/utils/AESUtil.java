package com.moin.globalRemittance.utils;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Component
public class AESUtil {

    private static final String AES = "AES";
    private static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 16;
    private static final int GCM_IV_LENGTH = 12;

    @Value("${variables.security.aes.secret-key}")
    private String secretKeyString;
    private byte[] secretKey;

    @PostConstruct
    public void init() {
        if (secretKeyString.length() != 32) {
            throw new IllegalArgumentException("Secret key must be 32 characters long");
        }
        secretKey = secretKeyString.getBytes();
    }

    public String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
        SecretKeySpec keySpec = new SecretKeySpec(secretKey, AES);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        byte[] iv = cipher.getIV();
        byte[] encryptedValue = cipher.doFinal(value.getBytes());

        byte[] encryptedValueWithIv = new byte[GCM_IV_LENGTH + encryptedValue.length];
        System.arraycopy(iv, 0, encryptedValueWithIv, 0, GCM_IV_LENGTH);
        System.arraycopy(encryptedValue, 0, encryptedValueWithIv, GCM_IV_LENGTH, encryptedValue.length);

        return Base64.getEncoder().encodeToString(encryptedValueWithIv);
    }

    public String decrypt(String encryptedValue) throws Exception {
        byte[] encryptedValueWithIv = Base64.getDecoder().decode(encryptedValue);
        Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
        SecretKeySpec keySpec = new SecretKeySpec(secretKey, AES);

        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * Byte.SIZE, encryptedValueWithIv, 0, GCM_IV_LENGTH);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmParameterSpec);

        byte[] decryptedValue = cipher.doFinal(encryptedValueWithIv, GCM_IV_LENGTH, encryptedValueWithIv.length - GCM_IV_LENGTH);

        return new String(decryptedValue);
    }
}
