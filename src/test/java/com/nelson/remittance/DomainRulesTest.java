package com.nelson.remittance;

import static com.nelson.remittance.domain.exception.ErrorCode.ALREADY_REQUESTED;
import static com.nelson.remittance.domain.exception.ErrorCode.FORBIDDEN;
import static com.nelson.remittance.domain.exception.ErrorCode.IDEMPOTENCY_KEY_REUSED;
import static com.nelson.remittance.domain.exception.ErrorCode.LIMIT_EXCESS;
import static com.nelson.remittance.domain.exception.ErrorCode.QUOTE_EXPRIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nelson.remittance.domain.exception.BusinessException;
import com.nelson.remittance.domain.exception.ErrorCode;
import com.nelson.remittance.domain.quote.Quote;
import com.nelson.remittance.domain.quote.QuotePolicy;
import com.nelson.remittance.domain.time.BusinessDay;
import com.nelson.remittance.domain.user.User;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// Spring 없이 기준일·한도·견적 발급·멱등성 재현 규칙의 경계를 고정 시각으로 검증한다.
class DomainRulesTest {
    private static final Instant NOW = Instant.parse("2026-03-10T03:00:00Z");

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private static void assertCode(ErrorCode code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).code());
    }

    private static User user(User.IdentityType type) {
        return new User(1, "u", "h", "n", type, "e", NOW, NOW);
    }

    private static Quote quote(long id, long userId, Instant requestedTime) {
        var calculation = QuotePolicy.calculate(n("100000"), "USD", n("1000"), n("1000"));
        Quote issued = Quote.issue(userId, calculation, NOW);
        return new Quote(
                id,
                userId,
                calculation,
                issued.expireTime(),
                requestedTime,
                requestedTime == null ? null : "key",
                NOW,
                NOW);
    }

    @Test
    void businessDayEndsAtKstMidnight() {
        // KST 23:59:59.999999999는 UTC 14:59:59.999999999다.
        BusinessDay before = BusinessDay.of(Instant.parse("2026-03-10T14:59:59.999999999Z"));
        BusinessDay at = BusinessDay.of(Instant.parse("2026-03-10T15:00:00Z"));
        assertEquals(Instant.parse("2026-03-09T15:00:00Z"), before.start());
        assertEquals(Instant.parse("2026-03-10T15:00:00Z"), before.end());
        assertEquals(before.end(), at.start());
        assertEquals(Instant.parse("2026-03-11T15:00:00Z"), at.end());
    }

    @Test
    // 현행 날짜(서머타임 없음)에서의 길이다. 과거 서머타임 구간은 검증 대상이 아니다.
    void businessDayIsExactlyTwentyFourHours() {
        BusinessDay day = BusinessDay.of(NOW);
        assertEquals(Duration.ofHours(24), Duration.between(day.start(), day.end()));
        assertEquals(Instant.parse("2026-03-09T15:00:00Z"), day.start());
    }

    @Test
    void businessDayCrossesYearAndMonthBoundary() {
        BusinessDay yearEnd = BusinessDay.of(Instant.parse("2026-12-31T15:00:00Z"));
        assertEquals(Instant.parse("2026-12-31T15:00:00Z"), yearEnd.start());
        assertEquals(Instant.parse("2027-01-01T15:00:00Z"), yearEnd.end());
        BusinessDay monthEnd = BusinessDay.of(Instant.parse("2026-02-28T14:59:59Z"));
        assertEquals(Instant.parse("2026-02-27T15:00:00Z"), monthEnd.start());
        assertEquals(Instant.parse("2026-02-28T15:00:00Z"), monthEnd.end());
    }

    @Test
    void dailyLimitAllowsExactAndRejectsOver() {
        User reg = user(User.IdentityType.REG_NO);
        reg.checkDailyLimit(n("900.00"), n("100.00"));
        assertCode(LIMIT_EXCESS, () -> reg.checkDailyLimit(n("900.00"), n("100.01")));
        User business = user(User.IdentityType.BUSINESS_NO);
        business.checkDailyLimit(n("4999.99"), n("0.01"));
        assertCode(LIMIT_EXCESS, () -> business.checkDailyLimit(n("4999.99"), n("0.02")));
    }

    @Test
    void dailyLimitRejectsSingleOverLimitFromZero() {
        assertCode(
                LIMIT_EXCESS,
                () -> user(User.IdentityType.REG_NO).checkDailyLimit(n("0"), n("1000.01")));
    }

    @Test
    void dailyLimitAllowsZeroAmountWhileWithinLimit() {
        User reg = user(User.IdentityType.REG_NO);
        reg.checkDailyLimit(n("1000.00"), n("0"));
        assertCode(LIMIT_EXCESS, () -> reg.checkDailyLimit(n("1000.01"), n("0")));
    }

    @Test
    void issueSetsTenMinuteExpiryAndUnrequestedState() {
        var calculation = QuotePolicy.calculate(n("100000"), "USD", n("1000"), n("1000"));
        Quote quote = Quote.issue(7, calculation, NOW);
        assertEquals(NOW.plus(Duration.ofMinutes(10)), quote.expireTime());
        assertEquals(7, quote.userId());
        assertNull(quote.requestedTime());
        assertNull(quote.idempotencyKey());
        assertEquals(NOW, quote.createdAt());
        assertEquals(NOW, quote.updatedAt());
    }

    @Test
    void replayWindowIsHalfOpenAtTwentyFourHours() {
        Instant requestedAt = NOW;
        Quote quote = quote(5, 1, requestedAt);
        Instant end = requestedAt.plus(Duration.ofHours(24));
        assertTrue(quote.replays(quote, end.minusNanos(1)));
        assertFalse(quote.replays(quote, end));
        assertFalse(quote.replays(quote, end.plusSeconds(1)));
    }

    @Test
    void replayWithDifferentQuoteIsKeyReuseRegardlessOfTime() {
        Quote quote = quote(5, 1, null);
        Quote previous = quote(6, 1, NOW);
        assertCode(IDEMPOTENCY_KEY_REUSED, () -> quote.replays(previous, NOW));
        assertCode(
                IDEMPOTENCY_KEY_REUSED,
                () -> quote.replays(previous, NOW.plus(Duration.ofDays(3))));
    }

    @Test
    void ownership() {
        Quote quote = quote(5, 1, null);
        assertTrue(quote.isOwnedBy(1));
        assertFalse(quote.isOwnedBy(2));
    }

    @Test
    void requestChecksOwnerThenDuplicateThenExpiry() {
        Quote fresh = quote(5, 1, null);
        Instant expired = fresh.expireTime();
        // 소유권 > 중복 > 만료: 타인+이미 접수+만료 상황에서도 FORBIDDEN이다.
        assertCode(FORBIDDEN, () -> quote(5, 1, NOW).request(2, expired, null));
        // 이미 접수된 견적은 만료 시각과 같거나 이후여도 ALREADY_REQUESTED다.
        assertCode(ALREADY_REQUESTED, () -> quote(5, 1, NOW).request(1, expired, null));
        assertCode(
                ALREADY_REQUESTED, () -> quote(5, 1, NOW).request(1, expired.plusSeconds(1), null));
        assertCode(ALREADY_REQUESTED, () -> quote(5, 1, NOW).request(1, NOW, null));
        assertCode(QUOTE_EXPRIED, () -> fresh.request(1, expired, null));
        assertEquals(
                fresh.expireTime().minusNanos(1),
                fresh.request(1, fresh.expireTime().minusNanos(1), "k").requestedTime());
    }
}
