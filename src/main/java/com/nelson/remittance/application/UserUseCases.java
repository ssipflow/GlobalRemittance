package com.nelson.remittance.application;

import static com.nelson.remittance.domain.BusinessException.Code.DUPLICATE_USER;
import static com.nelson.remittance.domain.BusinessException.Code.INVALID_REQUEST;
import static com.nelson.remittance.domain.BusinessException.Code.UNAUTHORIZED;

import com.nelson.remittance.application.port.AccountRepository;
import com.nelson.remittance.application.port.IdentityCipher;
import com.nelson.remittance.application.port.PasswordHasher;
import com.nelson.remittance.application.port.TokenProvider;
import com.nelson.remittance.domain.BusinessException;
import com.nelson.remittance.domain.User;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

// 가입과 로그인을 조정하며 암호화·해시·토큰·저장 구현을 포트로 호출한다.
// 프록시가 서브클래스를 만들어 @Transactional을 적용하므로 final로 두지 않는다.
public class UserUseCases {
    private final AccountRepository accounts;
    private final PasswordHasher passwords;
    private final IdentityCipher cipher;
    private final TokenProvider tokens;
    private final Clock clock;

    public UserUseCases(
            AccountRepository accounts,
            PasswordHasher passwords,
            IdentityCipher cipher,
            TokenProvider tokens,
            Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.cipher = cipher;
        this.tokens = tokens;
        this.clock = clock;
    }

    // 입력 바이트 수를 확인하고 중복 조회, 비밀번호 해시, 번호 암호화와 저장을 한 트랜잭션으로 수행한다.
    @Transactional
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
    }

    // 저장된 해시와 비밀번호를 비교한 뒤 토큰을 발급하며 실패 이유는 동일한 인증 오류로 처리한다.
    @Transactional
    public String login(String userId, String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(UNAUTHORIZED);
        }
        User user =
                accounts.findUser(userId).orElseThrow(() -> new BusinessException(UNAUTHORIZED));
        if (!passwords.matches(password, user.passwordHash())) {
            throw new BusinessException(UNAUTHORIZED);
        }
        return tokens.issue(user);
    }
}
