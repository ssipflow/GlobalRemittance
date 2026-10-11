package com.moin.remittance;

import static com.moin.remittance.domain.BusinessException.Code.ALREADY_REQUESTED;
import static com.moin.remittance.domain.BusinessException.Code.FORBIDDEN;
import static com.moin.remittance.domain.BusinessException.Code.INVALID_REQUEST;
import static com.moin.remittance.domain.BusinessException.Code.NEGATIVE_NUMBER;
import static com.moin.remittance.domain.BusinessException.Code.QUOTE_EXPRIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.moin.remittance.domain.BusinessException;
import com.moin.remittance.domain.Quote;
import com.moin.remittance.domain.QuotePolicy;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class QuotePolicyTest {
    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private static QuotePolicy.Calculation usd(String value) {
        return QuotePolicy.calculate(n(value), "USD", n("1000"), n("1000"));
    }

    private static void decimal(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual));
    }

    private static void failure(BusinessException.Code code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).code());
    }

    @Test
    void usdFeeChangesStrictlyAfterOneMillion() {
        var low = usd("1000000");
        var high = usd("1000001");
        decimal("1000", low.fixedFee());
        decimal("0.002", low.feeRatio());
        decimal("2000", low.ratedFee());
        decimal("997.00", low.targetAmount());
        decimal("3000", high.fixedFee());
        decimal("0.001", high.feeRatio());
        decimal("1000", high.ratedFee());
        decimal("996.00", high.usdAmount());
    }

    @Test
    void jpyUsesNetKrwAndZeroFractionDigits() {
        var quote = QuotePolicy.calculate(n("100000"), "JPY", n("10"), n("1000"));
        decimal("8000", quote.fee());
        decimal("9200", quote.targetAmount());
        decimal("92.00", quote.usdAmount());
        assertEquals(0, quote.targetAmount().scale());
        assertEquals(2, quote.usdAmount().scale());
    }

    @Test
    void krwAndTargetAmountsUseHalfUp() {
        var quote = usd("15000.5");
        decimal("15001", quote.sourceAmount());
        decimal("30", quote.ratedFee());
        var half = QuotePolicy.calculate(n("1003"), "USD", n("8"), n("8"));
        decimal("0.13", half.targetAmount());
        assertEquals(2, half.targetAmount().scale());
    }

    @Test
    void zeroNetOrRoundedZeroIsAllowedButNegativeIsNot() {
        decimal("0.00", usd("1002").targetAmount());
        var tiny = QuotePolicy.calculate(n("1003"), "USD", n("100000000"), n("100000000"));
        decimal("0.00", tiny.usdAmount());
        failure(NEGATIVE_NUMBER, () -> usd("-1"));
        failure(NEGATIVE_NUMBER, () -> usd("0"));
        failure(NEGATIVE_NUMBER, () -> usd("1000"));
    }

    @Test
    void foreignExchangeRateIsRoundedToTwelveDecimals() {
        var rate = QuotePolicy.rate(n("1300.1234567890126"));
        assertEquals(n("1300.123456789013"), rate);
    }

    @Test
    void invalidCurrencyRatesPrecisionAndExtremeExponentAreRejected() {
        failure(INVALID_REQUEST, () -> QuotePolicy.calculate(n("10000"), "EUR", n("1"), n("1")));
        failure(INVALID_REQUEST, () -> QuotePolicy.rate(n("0")));
        failure(INVALID_REQUEST, () -> QuotePolicy.rate(n("0.0000000000001")));
        failure(INVALID_REQUEST, () -> QuotePolicy.rate(n("1E+1000000000")));
        failure(INVALID_REQUEST, () -> usd("1E+1000000000"));
        failure(INVALID_REQUEST, () -> usd("10000000000"));
        failure(
                INVALID_REQUEST,
                () -> QuotePolicy.calculate(n("100000"), "USD", n("0.000000000001"), n("1000")));
    }

    @Test
    void decimalStorageCapacityAllowsFractionalUpperBandButRejectsRoundedOverflow() {
        var quote =
                QuotePolicy.calculate(
                        n("1001004004"), "USD", n("0.100000000001"), n("0.100000000001"));
        decimal("9999999999.90", quote.targetAmount());
        failure(
                INVALID_REQUEST,
                () -> QuotePolicy.calculate(n("1001004004"), "USD", n("0.1"), n("0.1")));
        failure(INVALID_REQUEST, () -> usd("9999999999.5"));
    }

    @Test
    void ownershipDuplicateAndExactExpiryBoundaryHaveStablePrecedence() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Quote quote = new Quote(1, 10, usd("100000"), now.plusSeconds(600), null, now, now);
        failure(FORBIDDEN, () -> quote.request(20, now.plusSeconds(600)));
        failure(QUOTE_EXPRIED, () -> quote.request(10, now.plusSeconds(600)));
        Quote accepted = quote.request(10, now.plusSeconds(599));
        failure(ALREADY_REQUESTED, () -> accepted.request(10, now.plusSeconds(600)));
    }
}
