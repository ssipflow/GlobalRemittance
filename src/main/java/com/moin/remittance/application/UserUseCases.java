package com.moin.remittance.application;

import static com.moin.remittance.domain.BusinessException.Code.DUPLICATE_USER;
import static com.moin.remittance.domain.BusinessException.Code.INVALID_REQUEST;
import static com.moin.remittance.domain.BusinessException.Code.UNAUTHORIZED;

import com.moin.remittance.domain.BusinessException;
import com.moin.remittance.domain.User;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

public final class UserUseCases {
    private final Ports.Accounts accounts;
    private final Ports.Passwords passwords;
    private final Ports.IdentityCipher cipher;
    private final Ports.Tokens tokens;
    private final Ports.Work work;
    private final Clock clock;

    public UserUseCases(
            Ports.Accounts accounts,
            Ports.Passwords passwords,
            Ports.IdentityCipher cipher,
            Ports.Tokens tokens,
            Ports.Work work,
            Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.cipher = cipher;
        this.tokens = tokens;
        this.work = work;
        this.clock = clock;
    }

    public void signup(
            String userId, String password, String name, User.IdentityType idType, String idValue) {
        // BCrypt는 UTF-8 72바이트까지만 처리하므로 문자 수와 별도로 검사해 잘림을 방지한다.
        if (password == null
                || password.isBlank()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(INVALID_REQUEST);
        }
        // nonce 12 + 원문 최대 64 + GCM tag 16바이트를 Base64로 저장하면 DB 128자 이내다.
        if (idValue == null
                || idValue.isBlank()
                || idValue.getBytes(StandardCharsets.UTF_8).length > 64) {
            throw new BusinessException(INVALID_REQUEST);
        }
        work.required(
                () -> {
                    if (accounts.findUser(userId).isPresent()) {
                        throw new BusinessException(DUPLICATE_USER);
                    }
                    Instant now = clock.instant();
                    accounts.saveUser(
                            new User(
                                    0,
                                    userId,
                                    passwords.hash(password),
                                    name,
                                    idType,
                                    cipher.encrypt(idValue),
                                    now,
                                    now));
                    return null;
                });
    }

    public String login(String userId, String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(UNAUTHORIZED);
        }
        return work.required(
                () -> {
                    User user =
                            accounts.findUser(userId)
                                    .orElseThrow(() -> new BusinessException(UNAUTHORIZED));
                    if (!passwords.matches(password, user.passwordHash())) {
                        throw new BusinessException(UNAUTHORIZED);
                    }
                    return tokens.issue(user);
                });
    }
}
