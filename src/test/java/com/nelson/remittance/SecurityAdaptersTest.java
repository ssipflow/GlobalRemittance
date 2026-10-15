package com.nelson.remittance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nelson.remittance.adapter.security.crypto.AesIdentityCipher;
import com.nelson.remittance.adapter.security.crypto.BCryptPasswordHasher;
import com.nelson.remittance.adapter.security.jwt.JwtTokens;
import com.nelson.remittance.domain.exception.BusinessException;
import com.nelson.remittance.domain.user.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

// 암호화·비밀번호·토큰 어댑터의 보안 계약을 독립 검증한다.
class SecurityAdaptersTest {
    private static final String KEY =
            Base64.getEncoder()
                    .encodeToString(
                            "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static User user() {
        return new User(
                1,
                "test@example.com",
                "unused",
                "홍길동",
                User.IdentityType.REG_NO,
                "encrypted",
                NOW,
                NOW);
    }

    @Test
    // 동일 원문도 다른 nonce로 암호화되고 변조된 암호문은 인증 실패하는지 확인한다.
    void aesGcmRoundTripsWithDifferentNonceAndAuthenticatesCiphertext() throws Exception {
        var cipher = new AesIdentityCipher(KEY);
        String raw = "1234567890123";
        String first = cipher.encrypt(raw), second = cipher.encrypt(raw);
        assertNotEquals(first, second);
        assertFalse(first.contains(raw));
        byte[] encoded = Base64.getDecoder().decode(first);
        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(Base64.getDecoder().decode(KEY), "AES"),
                new GCMParameterSpec(128, Arrays.copyOfRange(encoded, 0, 12)));
        decrypt.updateAAD("identity:v1".getBytes(StandardCharsets.UTF_8));
        assertEquals(
                raw,
                new String(
                        decrypt.doFinal(Arrays.copyOfRange(encoded, 12, encoded.length)),
                        StandardCharsets.UTF_8));
        encoded[encoded.length - 1] ^= 1;
        assertThrows(
                javax.crypto.AEADBadTagException.class,
                () -> decrypt.doFinal(Arrays.copyOfRange(encoded, 12, encoded.length)));
    }

    @Test
    // 잘못된 키는 즉시 실패하며 오류에 비밀 값이 포함되지 않는지 확인한다.
    void keysFailFastWithoutIncludingSecretValues() {
        assertThrows(IllegalArgumentException.class, () -> new AesIdentityCipher("short"));
        assertThrows(
                IllegalArgumentException.class, () -> new JwtTokens("short", Clock.systemUTC()));
    }

    @Test
    // BCrypt 솔트와 잘못된 비밀번호 거부를 검증한다.
    void bcryptHashesAreSaltedAndCannotAuthenticateWrongPassword() {
        var passwords = new BCryptPasswordHasher();
        String first = passwords.hash("test-password"), second = passwords.hash("test-password");
        assertNotEquals(first, second);
        assertTrue(first.startsWith("$2"));
        assertTrue(passwords.matches("test-password", first));
        assertFalse(passwords.matches("wrong-password", first));
    }

    @Test
    // 필수 JWT 클레임과 정확한 30분 만료 경계를 검증한다.
    void jwtCarriesRequiredClaimsAndExpiresAtThirtyMinutesExactly() {
        var tokens = new JwtTokens(KEY, Clock.fixed(NOW, ZoneOffset.UTC));
        String token = tokens.issue(user());
        assertEquals(1, tokens.verify(token));
        var claims =
                Jwts.parserBuilder()
                        .setSigningKey(Keys.hmacShaKeyFor(Base64.getDecoder().decode(KEY)))
                        .setClock(() -> Date.from(NOW))
                        .build()
                        .parseClaimsJws(token)
                        .getBody();
        assertEquals(1, ((Number) claims.get("id")).intValue());
        assertEquals("test@example.com", claims.get("userId"));
        assertEquals("홍길동", claims.get("name"));
        assertEquals("2026-01-01T09:30:00+09:00", claims.get("expireTime"));
        assertEquals(NOW.plusSeconds(1800), claims.getExpiration().toInstant());
        assertThrows(
                BusinessException.class,
                () ->
                        new JwtTokens(KEY, Clock.fixed(NOW.plusSeconds(1800), ZoneOffset.UTC))
                                .verify(token));
        String otherKey =
                Base64.getEncoder()
                        .encodeToString(
                                "abcdefghijabcdefghijabcdefghijab"
                                        .getBytes(StandardCharsets.UTF_8));
        assertThrows(
                BusinessException.class,
                () -> new JwtTokens(otherKey, Clock.fixed(NOW, ZoneOffset.UTC)).verify(token));
        assertThrows(BusinessException.class, () -> tokens.verify("not-a-jwt"));
    }
}
