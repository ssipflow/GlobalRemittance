package com.nelson.remittance.application;

import static com.nelson.remittance.domain.BusinessException.Code.LIMIT_EXCESS;
import static com.nelson.remittance.domain.BusinessException.Code.QUOTE_NOT_FOUND;
import static com.nelson.remittance.domain.BusinessException.Code.UNAUTHORIZED;

import com.nelson.remittance.domain.BusinessException;
import com.nelson.remittance.domain.Quote;
import com.nelson.remittance.domain.QuotePolicy;
import com.nelson.remittance.domain.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

public final class TransferUseCases {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Ports.Accounts accounts;
    private final Ports.Quotes quotes;
    private final Ports.Rates rates;
    private final Ports.Work work;
    private final Clock clock;

    public TransferUseCases(
            Ports.Accounts accounts,
            Ports.Quotes quotes,
            Ports.Rates rates,
            Ports.Work work,
            Clock clock) {
        this.accounts = accounts;
        this.quotes = quotes;
        this.rates = rates;
        this.work = work;
        this.clock = clock;
    }

    public Quote quote(long actorId, BigDecimal amount, String currency) {
        return work.required(
                () -> {
                    accounts.findUser(actorId)
                            .orElseThrow(() -> new BusinessException(UNAUTHORIZED));
                    var calculation =
                            QuotePolicy.calculate(
                                    amount, currency, rates.toKrw(currency), rates.toKrw("USD"));
                    Instant now = clock.instant();
                    return quotes.saveQuote(
                            new Quote(
                                    0, actorId, calculation, now.plusSeconds(600), null, now, now));
                });
    }

    public void request(long actorId, long quoteId) {
        work.required(
                () -> {
                    // 사용자 행을 먼저 잠가 일일 합계 조회와 접수를 직렬화한다. 다른 견적 두 개도 한도를 넘을 수 없다.
                    User user = accounts.lockUser(actorId);
                    Quote quote =
                            quotes.findQuote(quoteId)
                                    .orElseThrow(() -> new BusinessException(QUOTE_NOT_FOUND));
                    Instant now = clock.instant();
                    Quote requested = quote.request(actorId, now);
                    BigDecimal total =
                            today(quotes.requestedQuotes(actorId), now).stream()
                                    .map(q -> q.calculation().usdAmount())
                                    .reduce(new BigDecimal("0.00"), BigDecimal::add);
                    // 한도와 동일한 금액은 허용한다. USD 수수료 차감 후 금액은 견적 시 확정된 값만 사용한다.
                    if (total.add(quote.calculation().usdAmount())
                                    .compareTo(user.idType().dailyLimit())
                            > 0) {
                        throw new BusinessException(LIMIT_EXCESS);
                    }
                    quotes.saveQuote(requested);
                    return null;
                });
    }

    public record History(
            User user, int todayCount, BigDecimal todayUsdAmount, List<Quote> quotes) {}

    public History history(long actorId) {
        return work.required(
                () -> {
                    User user = accounts.lockUser(actorId);
                    List<Quote> all = quotes.requestedQuotes(actorId);
                    List<Quote> today = today(all, clock.instant());
                    BigDecimal sum =
                            today.stream()
                                    .map(q -> q.calculation().usdAmount())
                                    .reduce(new BigDecimal("0.00"), BigDecimal::add);
                    return new History(user, today.size(), sum, List.copyOf(all));
                });
    }

    private static List<Quote> today(List<Quote> all, Instant now) {
        var date = now.atZone(KST).toLocalDate();
        return all.stream()
                .filter(
                        q ->
                                q.requestedTime() != null
                                        && q.requestedTime().atZone(KST).toLocalDate().equals(date))
                .toList();
    }
}
