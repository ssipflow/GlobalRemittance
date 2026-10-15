package com.nelson.remittance.application.port.repository;

import com.nelson.remittance.domain.quote.Quote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

// 견적 계산값과 접수 상태를 저장하고 사용자별 접수 이력을 제공한다.
public interface QuoteRepository {
    Optional<Quote> findQuote(long id);

    Quote saveQuote(Quote quote);

    // 사용자 범위에서 멱등성 키로 접수된 견적을 찾는다. 다른 사용자의 같은 키 문자열은 보이지 않는다.
    Optional<Quote> findByIdempotencyKey(long userId, String idempotencyKey);

    // 접수 시각·ID 내림차순으로 after 다음 항목부터 최대 limit건을 읽는다. after가 null이면 처음부터다.
    // 미접수 견적은 제외하며 조회 대상은 항상 userId의 견적으로 제한된다.
    List<Quote> requestedPage(long userId, QuotePosition after, int limit);

    // [from, to) 구간에 접수된 견적의 건수와 USD 합계를 한 쿼리로 집계한다. 없으면 0건, 0.00이다.
    DailyTotal requestedDailyTotal(long userId, Instant from, Instant to);

    // [from, to) 구간에 접수된 견적의 USD 금액 합계다. 접수 건이 없으면 0.00을 돌려준다.
    BigDecimal requestedUsdTotal(long userId, Instant from, Instant to);
}
