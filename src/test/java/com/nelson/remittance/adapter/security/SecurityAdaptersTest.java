package com.nelson.remittance.adapter.security;

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

    // 호출 횟수와 인자를 기록하는 해시기와 메모리 저장소로 사용 사례의 정규화·타이밍 균등화를 검증한다.
    private static final class Fixture {
        final java.util.List<String> encodedSeen = new java.util.ArrayList<>();
        final java.util.Map<String, User> users = new java.util.HashMap<>();
        final BCryptPasswordHasher real = new BCryptPasswordHasher();
        final com.nelson.remittance.application.port.security.PasswordHasher hasher =
                new com.nelson.remittance.application.port.security.PasswordHasher() {
                    public String hash(String raw) {
                        return real.hash(raw);
                    }

                    public boolean matches(String raw, String encoded) {
                        encodedSeen.add(encoded);
                        return real.matches(raw, encoded);
                    }
                };
        final com.nelson.remittance.application.user.UserUseCases useCases =
                new com.nelson.remittance.application.user.UserUseCases(
                        new com.nelson.remittance.application.port.repository.AccountRepository() {
                            public java.util.Optional<User> findUser(long id) {
                                return java.util.Optional.empty();
                            }

                            public java.util.Optional<User> findUser(String userId) {
                                return java.util.Optional.ofNullable(users.get(userId));
                            }

                            public User lockUser(long id) {
                                throw new UnsupportedOperationException();
                            }

                            public User saveUser(User user) {
                                users.put(user.userId(), user);
                                return user;
                            }
                        },
                        hasher,
                        new AesIdentityCipher(KEY),
                        new JwtTokens(KEY, Clock.fixed(NOW, ZoneOffset.UTC)),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    // 존재하지 않는 사용자 로그인도 더미 해시와 matches를 수행해 비용을 맞추고 같은 UNAUTHORIZED를 돌려준다.
    void loginForUnknownUserStillComparesAgainstDummyHash() {
        var fixture = new Fixture();
        fixture.encodedSeen.clear();
        var error =
                assertThrows(
                        BusinessException.class,
                        () -> fixture.useCases.login("nobody@example.com", "test-password"));
        assertEquals(com.nelson.remittance.domain.exception.ErrorCode.UNAUTHORIZED, error.code());
        assertEquals(1, fixture.encodedSeen.size());
        assertTrue(fixture.encodedSeen.get(0).startsWith("$2"), "더미 해시도 BCrypt 형식이어야 한다");

        // 비밀번호가 틀린 기존 사용자도 같은 오류와 같은 횟수의 비교를 거친다.
        fixture.useCases.signup(
                "known@example.com", "test-password", "홍길동", User.IdentityType.REG_NO, "id-1");
        fixture.encodedSeen.clear();
        var wrong =
                assertThrows(
                        BusinessException.class,
                        () -> fixture.useCases.login("known@example.com", "wrong-password"));
        assertEquals(error.code(), wrong.code());
        assertEquals(1, fixture.encodedSeen.size());
    }

    @Test
    // 가입은 소문자 정규화 값을 저장하고, 대소문자만 다른 가입은 중복이며 로그인은 어떤 표기로도 성공한다.
    void signupAndLoginNormalizeEmailCase() {
        var fixture = new Fixture();
        fixture.useCases.signup(
                "Alice@Example.COM", "test-password", "홍길동", User.IdentityType.REG_NO, "id-1");
        assertEquals(java.util.Set.of("alice@example.com"), fixture.users.keySet());
        var duplicate =
                assertThrows(
                        BusinessException.class,
                        () ->
                                fixture.useCases.signup(
                                        "alice@example.com",
                                        "test-password",
                                        "홍길동",
                                        User.IdentityType.REG_NO,
                                        "id-2"));
        assertEquals(
                com.nelson.remittance.domain.exception.ErrorCode.DUPLICATE_USER, duplicate.code());
        assertFalse(fixture.useCases.login("ALICE@example.com", "test-password").isEmpty());
        assertFalse(fixture.useCases.login("alice@example.com", "test-password").isEmpty());
    }
}
