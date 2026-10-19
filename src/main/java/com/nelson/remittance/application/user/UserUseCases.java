package com.nelson.remittance.application.user;

import static com.nelson.remittance.domain.exception.ErrorCode.DUPLICATE_USER;
import static com.nelson.remittance.domain.exception.ErrorCode.INVALID_REQUEST;
import static com.nelson.remittance.domain.exception.ErrorCode.UNAUTHORIZED;

import com.nelson.remittance.application.port.repository.AccountRepository;
import com.nelson.remittance.application.port.security.IdentityCipher;
import com.nelson.remittance.application.port.security.PasswordHasher;
import com.nelson.remittance.application.port.security.TokenProvider;
import com.nelson.remittance.domain.exception.BusinessException;
import com.nelson.remittance.domain.user.User;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

// 가입과 로그인을 조정하며 암호화·해시·토큰·저장 구현을 포트로 호출한다.
// 컴포넌트 스캔으로 등록되는 빈이며 생성자 주입 대상은 모두 포트다.
@Service
public class UserUseCases {
    private final AccountRepository accountRepository;
    private final PasswordHasher passwordHasher;
    private final IdentityCipher identityCipher;
    private final TokenProvider tokenProvider;
    private final Clock clock;
    // 없는 사용자 로그인에서도 해시 비교를 수행해 응답 시간 차이로 계정 존재 여부가 드러나지 않게 하는 해시다.
    // 기동 시 임의 값으로 같은 해시기(같은 cost)가 만들므로 고정 비밀 값을 코드에 두지 않는다.
    private final String dummyHash;

    public UserUseCases(
            AccountRepository accountRepository,
            PasswordHasher passwordHasher,
            IdentityCipher identityCipher,
            TokenProvider tokenProvider,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.passwordHasher = passwordHasher;
        this.identityCipher = identityCipher;
        this.tokenProvider = tokenProvider;
        this.clock = clock;
        this.dummyHash = passwordHasher.hash(UUID.randomUUID().toString());
    }

    // 이메일은 대소문자를 구분하지 않는 식별자로 취급해 소문자(Locale.ROOT)로 정규화한다.
    // 'Alice@x.com'과 'alice@x.com'이 별개 계정으로 가입되는 우회를 막고, 저장·조회가 같은 값을 쓰게 한다.
    // 앞뒤 공백은 입력 검증(@Email)이 이미 거부하므로 별도로 다듬지 않는다.
    static String normalizeUserId(String userId) {
        return userId == null ? null : userId.toLowerCase(Locale.ROOT);
    }

    // 입력 바이트 수를 확인하고 중복 조회, 비밀번호 해시, 번호 암호화와 저장을 수행한다.
    // 수백 ms가 걸리는 BCrypt 동안 DB 연결을 쥐지 않도록 이 메서드는 트랜잭션을 열지 않는다.
    // 조회는 연결을 즉시 반납하고, 저장만 어댑터가 짧은 트랜잭션으로 감싼다.
    // 사전 중복 조회와 저장 사이에 동시 가입이 끼어들 수 있으나 DB 유일 제약이 DUPLICATE_USER로 최종 방어한다.
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
        if (userId == null) {
            throw new BusinessException(INVALID_REQUEST);
        }
        userId = normalizeUserId(userId);
        // 소문자 변환은 일부 문자(예: İ)를 늘릴 수 있어 정규화 뒤 길이를 다시 확인한다(컬럼 길이 위반 500 방지).
        if (userId.length() > 255) {
            throw new BusinessException(INVALID_REQUEST);
        }
        if (accountRepository.findUser(userId).isPresent()) {
            throw new BusinessException(DUPLICATE_USER);
        }
        Instant now = clock.instant();
        accountRepository.saveUser(
                new User(
                        0,
                        userId,
                        passwordHasher.hash(password),
                        name,
                        idType,
                        identityCipher.encrypt(idValue),
                        now,
                        now));
    }

    // 저장된 해시와 비밀번호를 비교한 뒤 토큰을 발급하며 실패 이유는 동일한 인증 오류로 처리한다.
    // 읽기만 하므로 트랜잭션이 필요 없고, 느린 BCrypt 비교 동안 DB 연결을 쥐지 않는다.
    public String login(String userId, String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(UNAUTHORIZED);
        }
        // 사용자가 없어도 더미 해시와 비교해 BCrypt 비용을 동일하게 치르고, 실패 이유는 구분하지 않는다.
        Optional<User> found = accountRepository.findUser(normalizeUserId(userId));
        boolean matched =
                passwordHasher.matches(password, found.map(User::passwordHash).orElse(dummyHash));
        if (found.isEmpty() || !matched) {
            throw new BusinessException(UNAUTHORIZED);
        }
        return tokenProvider.issue(found.get());
    }
}
