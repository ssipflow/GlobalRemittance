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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

// 견적 생성, 원자적 접수와 KST 이력 집계를 트랜잭션 안에서 조정한다.
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

    // 존재하는 사용자에 대해 현재 구성 환율과 수수료를 확정하고 10분짜리 견적을 저장한다.
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

    // 사용자 잠금 안에서 견적 상태와 오늘 USD 합계를 검사한 뒤 접수 시각을 저장한다.
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
                    // 이력 전체를 읽지 않고 KST 당일 [00:00, 익일 00:00) 합계만 DB에서 집계한다.
                    LocalDate date = now.atZone(KST).toLocalDate();
                    BigDecimal total =
                            quotes.requestedUsdTotal(
                                    actorId,
                                    date.atStartOfDay(KST).toInstant(),
                                    date.plusDays(1).atStartOfDay(KST).toInstant());
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

    // nextAfter는 다음 페이지가 있을 때만 값이 있으며 이 페이지 마지막 항목의 위치다.
    public record History(
            User user,
            int todayCount,
            BigDecimal todayUsdAmount,
            List<Quote> quotes,
            Ports.Quotes.Position nextAfter) {}

    // 사용자 잠금 없이 읽는다. 조회가 접수 직렬화에 참여해 대기하거나 막지 않는다.
    // 오늘 건수·합계는 목록과 별개의 한 집계 쿼리로 구하므로 어느 페이지에서도 같고 서로 모순되지 않지만,
    // 접수와 겹치면 목록과 집계의 시점이 다를 수 있다. limit+1건을 읽어 다음 페이지 존재를 판단한다.
    public History history(long actorId, Ports.Quotes.Position after, int limit) {
        return work.required(
                () -> {
                    User user =
                            accounts.findUser(actorId)
                                    .orElseThrow(() -> new BusinessException(UNAUTHORIZED));
                    LocalDate date = clock.instant().atZone(KST).toLocalDate();
                    var total =
                            quotes.requestedDailyTotal(
                                    actorId,
                                    date.atStartOfDay(KST).toInstant(),
                                    date.plusDays(1).atStartOfDay(KST).toInstant());
                    List<Quote> read = quotes.requestedPage(actorId, after, limit + 1);
                    boolean more = read.size() > limit;
                    List<Quote> page = List.copyOf(more ? read.subList(0, limit) : read);
                    Ports.Quotes.Position next =
                            more
                                    ? new Ports.Quotes.Position(
                                            page.get(limit - 1).requestedTime(),
                                            page.get(limit - 1).id())
                                    : null;
                    return new History(user, total.count(), total.usdAmount(), page, next);
                });
    }
}
