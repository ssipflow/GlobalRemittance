package com.nelson.remittance.application;

import com.nelson.remittance.domain.Quote;
import com.nelson.remittance.domain.RateSnapshot;
import com.nelson.remittance.domain.User;
import java.math.BigDecimal;
import java.time.Instant;
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

        // 사용자 범위에서 멱등성 키로 접수된 견적을 찾는다. 다른 사용자의 같은 키 문자열은 보이지 않는다.
        Optional<Quote> findByIdempotencyKey(long userId, String idempotencyKey);

        // 접수 목록 위치다. 마지막으로 읽은 항목의 (접수 시각, ID)이며 인코딩 방식은 모른다.
        record Position(Instant requestedTime, long id) {}

        // 접수 건수와 USD 합계를 한 번의 조회 결과로 묶어 서로 모순되지 않게 한다.
        record DailyTotal(int count, BigDecimal usdAmount) {}

        // 접수 시각·ID 내림차순으로 after 다음 항목부터 최대 limit건을 읽는다. after가 null이면 처음부터다.
        // 미접수 견적은 제외하며 조회 대상은 항상 userId의 견적으로 제한된다.
        List<Quote> requestedPage(long userId, Position after, int limit);

        // [from, to) 구간에 접수된 견적의 건수와 USD 합계를 한 쿼리로 집계한다. 없으면 0건, 0.00이다.
        DailyTotal requestedDailyTotal(long userId, Instant from, Instant to);

        // [from, to) 구간에 접수된 견적의 USD 금액 합계다. 접수 건이 없으면 0.00을 돌려준다.
        BigDecimal requestedUsdTotal(long userId, Instant from, Instant to);
    }

    // 대상 통화 한 단위당 KRW 환율을 기준 시각과 함께 제공하며 내부 계층은 제공처 구현을 알지 못한다.
    // 공급 실패는 어댑터가 BusinessException(RATE_UNAVAILABLE)로 알리고, 미지원 통화는 INVALID_REQUEST다.
    // 신선도 판단은 정책이므로 어댑터가 아니라 사용 사례가 맡는다.
    public interface Rates {
        RateSnapshot rate(String currency);
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
