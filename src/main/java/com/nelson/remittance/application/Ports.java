package com.nelson.remittance.application;

import com.nelson.remittance.domain.Quote;
import com.nelson.remittance.domain.User;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

// 사용 사례가 필요한 외부 기능의 계약이다. 구현 기술은 어댑터에서 선택한다.
public final class Ports {
    private Ports() {}

    // 계정 조회·저장과 사용자 행 잠금의 계약이다. 잠금은 호출 트랜잭션까지 유지된다.
    public interface Accounts {
        Optional<User> findUser(long id);

        Optional<User> findUser(String userId);

        User lockUser(long id);

        User saveUser(User user);
    }

    // 견적 계산값과 접수 상태를 저장하고 사용자별 접수 이력을 제공한다.
    public interface Quotes {
        Optional<Quote> findQuote(long id);

        Quote saveQuote(Quote quote);

        List<Quote> requestedQuotes(long userId);
    }

    // 대상 통화 한 단위당 KRW 환율을 제공하며 내부 계층은 제공처 구현을 알지 못한다.
    public interface Rates {
        BigDecimal toKrw(String currency);
    }

    // 원문 비밀번호를 단방향 해시하고 저장된 해시와 비교한다.
    public interface Passwords {
        String hash(String raw);

        boolean matches(String raw, String encoded);
    }

    // 저장할 식별 번호를 암호화하며 개인 정보 복호화 기능은 사용 사례에 노출하지 않는다.
    public interface IdentityCipher {
        String encrypt(String value);
    }

    // 토큰을 발급하고 정상 서명·만료 조건을 통과한 사용자 식별자만 돌려준다.
    public interface Tokens {
        String issue(User user);

        long verify(String token);
    }

    // 트랜잭션 구현을 내부 정책에서 분리한다. 요청 처리의 사용자 잠금도 같은 트랜잭션에 속한다.
    public interface Work {
        <T> T required(Supplier<T> action);
    }
}
