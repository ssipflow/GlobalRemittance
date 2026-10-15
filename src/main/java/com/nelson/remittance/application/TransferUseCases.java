package com.nelson.remittance.application;

import static com.nelson.remittance.domain.BusinessException.Code.QUOTE_NOT_FOUND;
import static com.nelson.remittance.domain.BusinessException.Code.RATE_STALE;
import static com.nelson.remittance.domain.BusinessException.Code.UNAUTHORIZED;

import com.nelson.remittance.application.port.AccountRepository;
import com.nelson.remittance.application.port.QuotePosition;
import com.nelson.remittance.application.port.QuoteRepository;
import com.nelson.remittance.application.port.RateProvider;
import com.nelson.remittance.domain.BusinessDay;
import com.nelson.remittance.domain.BusinessException;
import com.nelson.remittance.domain.Quote;
import com.nelson.remittance.domain.QuotePolicy;
import com.nelson.remittance.domain.RateSnapshot;
import com.nelson.remittance.domain.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

// 견적 생성, 원자적 접수와 KST 이력 집계를 트랜잭션 안에서 조정한다.
// 프록시가 서브클래스를 만들어 @Transactional을 적용하므로 final로 두지 않는다.
public class TransferUseCases {
    private final AccountRepository accounts;
    private final QuoteRepository quotes;
    private final RateProvider rates;
    private final Clock clock;
    private final Duration rateMaxAge;

    public TransferUseCases(
            AccountRepository accounts,
            QuoteRepository quotes,
            RateProvider rates,
            Clock clock,
            Duration rateMaxAge) {
        this.accounts = accounts;
        this.quotes = quotes;
        this.rates = rates;
        this.clock = clock;
        this.rateMaxAge = rateMaxAge;
    }

    // 존재하는 사용자에 대해 신선한 환율과 수수료를 확정하고 10분짜리 견적을 저장한다.
    // 환율 조회는 발급 시점에만 한다. 발급된 견적은 10분간 환율이 고정이므로 접수(request)는 환율 상태와 무관하다.
    // 실제 외부 공급처를 붙이면 이 호출이 트랜잭션 안에서 일어나 DB 연결을 붙잡는다는 점을 재검토해야 한다.
    @Transactional
    public Quote quote(long actorId, BigDecimal amount, String currency) {
        accounts.findUser(actorId).orElseThrow(() -> new BusinessException(UNAUTHORIZED));
        // 대상이 USD면 한 번만 조회해 두 환율이 같은 스냅샷에서 나오게 한다.
        RateSnapshot target = rates.rate(currency);
        RateSnapshot usd = "USD".equals(currency) ? target : rates.rate("USD");
        Instant now = clock.instant();
        if (!target.isFresh(now, rateMaxAge) || !usd.isFresh(now, rateMaxAge)) {
            throw new BusinessException(RATE_STALE);
        }
        var calculation = QuotePolicy.calculate(amount, currency, target.toKrw(), usd.toKrw());
        return quotes.saveQuote(Quote.issue(actorId, calculation, now));
    }

    // 사용자 잠금 안에서 견적 상태와 오늘 USD 합계를 검사한 뒤 접수 시각을 저장한다.
    // 멱등성 키가 있으면 같은 키·같은 견적의 재시도는 아무것도 바꾸지 않고 성공으로 돌려준다.
    @Transactional
    public void request(long actorId, long quoteId, String idempotencyKey) {
        // 사용자 행을 먼저 잠가 일일 합계 조회와 접수를 직렬화한다. 다른 견적 두 개도 한도를 넘을 수 없다.
        User user = accounts.lockUser(actorId);
        Quote quote =
                quotes.findQuote(quoteId).orElseThrow(() -> new BusinessException(QUOTE_NOT_FOUND));
        Instant now = clock.instant();
        // 키 조회도 사용자 잠금 안이라 같은 키의 동시 재시도가 한 번만 반영된다.
        // 타인 견적은 키 판단 없이 아래 소유권 검증(FORBIDDEN)에 맡긴다.
        if (idempotencyKey != null && quote.isOwnedBy(actorId)) {
            var previous = quotes.findByIdempotencyKey(actorId, idempotencyKey);
            if (previous.isPresent() && quote.replays(previous.get(), now)) {
                return;
            }
        }
        Quote requested = quote.request(actorId, now, idempotencyKey);
        // 이력 전체를 읽지 않고 KST 당일 [00:00, 익일 00:00) 합계만 DB에서 집계한다.
        BusinessDay day = BusinessDay.of(now);
        BigDecimal total = quotes.requestedUsdTotal(actorId, day.start(), day.end());
        user.checkDailyLimit(total, quote.calculation().usdAmount());
        quotes.saveQuote(requested);
    }

    // nextAfter는 다음 페이지가 있을 때만 값이 있으며 이 페이지 마지막 항목의 위치다.
    public record History(
            User user,
            int todayCount,
            BigDecimal todayUsdAmount,
            List<Quote> quotes,
            QuotePosition nextAfter) {}

    // 사용자 잠금 없이 읽는다. 조회가 접수 직렬화에 참여해 대기하거나 막지 않는다.
    // 오늘 건수·합계는 목록과 별개의 한 집계 쿼리로 구하므로 어느 페이지에서도 같고 서로 모순되지 않지만,
    // 접수와 겹치면 목록과 집계의 시점이 다를 수 있다. limit+1건을 읽어 다음 페이지 존재를 판단한다.
    @Transactional
    public History history(long actorId, QuotePosition after, int limit) {
        User user =
                accounts.findUser(actorId).orElseThrow(() -> new BusinessException(UNAUTHORIZED));
        BusinessDay day = BusinessDay.of(clock.instant());
        var total = quotes.requestedDailyTotal(actorId, day.start(), day.end());
        List<Quote> read = quotes.requestedPage(actorId, after, limit + 1);
        boolean more = read.size() > limit;
        List<Quote> page = List.copyOf(more ? read.subList(0, limit) : read);
        QuotePosition next =
                more
                        ? new QuotePosition(
                                page.get(limit - 1).requestedTime(), page.get(limit - 1).id())
                        : null;
        return new History(user, total.count(), total.usdAmount(), page, next);
    }
}
