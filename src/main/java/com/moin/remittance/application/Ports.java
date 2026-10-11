package com.moin.remittance.application;

import com.moin.remittance.domain.Quote;
import com.moin.remittance.domain.User;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class Ports {
    private Ports() {}

    public interface Accounts {
        Optional<User> findUser(long id);

        Optional<User> findUser(String userId);

        User lockUser(long id);

        User saveUser(User user);
    }

    public interface Quotes {
        Optional<Quote> findQuote(long id);

        Quote saveQuote(Quote quote);

        List<Quote> requestedQuotes(long userId);
    }

    public interface Rates {
        BigDecimal toKrw(String currency);
    }

    public interface Passwords {
        String hash(String raw);

        boolean matches(String raw, String encoded);
    }

    public interface IdentityCipher {
        String encrypt(String value);
    }

    public interface Tokens {
        String issue(User user);

        long verify(String token);
    }

    // 트랜잭션 구현을 내부 정책에서 분리한다. 요청 처리의 사용자 잠금도 같은 트랜잭션에 속한다.
    public interface Work {
        <T> T required(Supplier<T> action);
    }
}
