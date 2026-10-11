package com.nelson.remittance;

import static com.nelson.remittance.domain.BusinessException.Code.ALREADY_REQUESTED;
import static com.nelson.remittance.domain.BusinessException.Code.FORBIDDEN;
import static com.nelson.remittance.domain.BusinessException.Code.INVALID_REQUEST;
import static com.nelson.remittance.domain.BusinessException.Code.NEGATIVE_NUMBER;
import static com.nelson.remittance.domain.BusinessException.Code.QUOTE_EXPRIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nelson.remittance.domain.BusinessException;
import com.nelson.remittance.domain.Quote;
import com.nelson.remittance.domain.QuotePolicy;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// 프레임워크 없이 금액·환율·업무 경계 규칙을 검증한다.
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
    // 100만 원 경계의 포함 여부와 USD 수수료 전환을 검증한다.
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
    // JPY 순액 환산과 소수 없는 반올림을 검증한다.
    void jpyUsesNetKrwAndZeroFractionDigits() {
        var quote = QuotePolicy.calculate(n("100000"), "JPY", n("10"), n("1000"));
        decimal("8000", quote.fee());
        decimal("9200", quote.targetAmount());
        decimal("92.00", quote.usdAmount());
        assertEquals(0, quote.targetAmount().scale());
        assertEquals(2, quote.usdAmount().scale());
    }

    @Test
    // 원화 입력과 수취 금액에 HALF_UP을 일관되게 적용하는지 확인한다.
    void krwAndTargetAmountsUseHalfUp() {
        var quote = usd("15000.5");
        decimal("15001", quote.sourceAmount());
        decimal("30", quote.ratedFee());
        var half = QuotePolicy.calculate(n("1003"), "USD", n("8"), n("8"));
        decimal("0.13", half.targetAmount());
        assertEquals(2, half.targetAmount().scale());
    }

    @Test
    // 0과 반올림 결과 0은 허용하고 음수 결과만 거부하는지 확인한다.
    void zeroNetOrRoundedZeroIsAllowedButNegativeIsNot() {
        decimal("0.00", usd("1002").targetAmount());
        var tiny = QuotePolicy.calculate(n("1003"), "USD", n("100000000"), n("100000000"));
        decimal("0.00", tiny.usdAmount());
        failure(NEGATIVE_NUMBER, () -> usd("-1"));
        failure(NEGATIVE_NUMBER, () -> usd("0"));
        failure(NEGATIVE_NUMBER, () -> usd("1000"));
    }

    @Test
    // 환율을 소수 12자리로 반올림하는 규칙을 확인한다.
    void foreignExchangeRateIsRoundedToTwelveDecimals() {
        var rate = QuotePolicy.rate(n("1300.1234567890126"));
        assertEquals(n("1300.123456789013"), rate);
    }

    @Test
    // 잘못된 통화·환율·정밀도와 큰 지수 입력을 계산 전에 거부하는지 확인한다.
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
    // DB 저장 상한의 소수 부분은 허용하되 반올림 후 초과는 거부하는지 확인한다.
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
    // 소유권·중복·정확한 만료 경계의 오류 우선순위를 검증한다.
    void ownershipDuplicateAndExactExpiryBoundaryHaveStablePrecedence() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Quote quote = new Quote(1, 10, usd("100000"), now.plusSeconds(600), null, now, now);
        failure(FORBIDDEN, () -> quote.request(20, now.plusSeconds(600)));
        failure(QUOTE_EXPRIED, () -> quote.request(10, now.plusSeconds(600)));
        Quote accepted = quote.request(10, now.plusSeconds(599));
        failure(ALREADY_REQUESTED, () -> accepted.request(10, now.plusSeconds(600)));
    }
}
