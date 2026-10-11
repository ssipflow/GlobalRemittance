package com.nelson.remittance.domain;

import static com.nelson.remittance.domain.BusinessException.Code.INVALID_REQUEST;
import static com.nelson.remittance.domain.BusinessException.Code.NEGATIVE_NUMBER;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

// 수수료와 순송금액을 십진수로 계산하고 통화 자릿수와 저장 범위를 검증한다.
public final class QuotePolicy {
    private static final BigDecimal MAX = new BigDecimal("9999999999.999999999999");
    private static final BigDecimal THRESHOLD = new BigDecimal("1000000");

    private QuotePolicy() {}

    public record Calculation(
            BigDecimal sourceAmount,
            String targetCurrency,
            BigDecimal exchangeRate,
            BigDecimal fixedFee,
            BigDecimal ratedFee,
            BigDecimal feeRatio,
            BigDecimal targetAmount,
            BigDecimal usdExchangeRate,
            BigDecimal usdAmount) {
        public BigDecimal fee() {
            return fixedFee.add(ratedFee);
        }
    }

    // KRW 입력을 정규화한 뒤 구간별 수수료를 빼고 확정 환율로 수취액과 USD 금액을 구한다.
    public static Calculation calculate(
            BigDecimal amount, String target, BigDecimal rate, BigDecimal usdRate) {
        if (amount == null || !("USD".equals(target) || "JPY".equals(target))) {
            throw new BusinessException(INVALID_REQUEST);
        }
        if (amount.signum() <= 0) {
            throw new BusinessException(NEGATIVE_NUMBER);
        }
        // 과도한 exponent/정밀도는 setScale의 큰 메모리 할당 전에 차단한다.
        if (amount.precision() > 22 || amount.scale() < -10 || amount.scale() > 12) {
            throw new BusinessException(INVALID_REQUEST);
        }
        BigDecimal source = money(amount, "KRW");
        if (source.compareTo(MAX) > 0 || amount.scale() > 12) {
            throw new BusinessException(INVALID_REQUEST);
        }
        rate = rate(rate);
        usdRate = rate(usdRate);
        boolean smallUsd = "USD".equals(target) && source.compareTo(THRESHOLD) <= 0;
        BigDecimal fixed = new BigDecimal(smallUsd ? "1000" : "3000");
        BigDecimal ratio =
                new BigDecimal("JPY".equals(target) ? "0.05" : smallUsd ? "0.002" : "0.001");
        BigDecimal rated = money(source.multiply(ratio), "KRW");
        BigDecimal net = source.subtract(fixed).subtract(rated);
        if (net.signum() < 0) {
            throw new BusinessException(NEGATIVE_NUMBER);
        }
        // 환율은 '대상 통화 1단위당 KRW'다. 곱셈이 아니라 순송금 KRW를 나눈다.
        BigDecimal received =
                net.divide(
                        rate,
                        Currency.getInstance(target).getDefaultFractionDigits(),
                        RoundingMode.HALF_UP);
        BigDecimal usd =
                net.divide(
                        usdRate,
                        Currency.getInstance("USD").getDefaultFractionDigits(),
                        RoundingMode.HALF_UP);
        if (received.signum() < 0 || usd.signum() < 0) {
            throw new BusinessException(NEGATIVE_NUMBER);
        }
        if (received.compareTo(MAX) > 0 || usd.compareTo(MAX) > 0) {
            throw new BusinessException(INVALID_REQUEST);
        }
        return new Calculation(source, target, rate, fixed, rated, ratio, received, usdRate, usd);
    }

    // 금액을 통화의 기본 소수 자릿수에 맞춰 HALF_UP으로 반올림한다.
    public static BigDecimal money(BigDecimal value, String currency) {
        return value.setScale(
                Currency.getInstance(currency).getDefaultFractionDigits(), RoundingMode.HALF_UP);
    }

    // 큰 지수의 메모리 할당을 먼저 막고 12자리 반올림 후 양수·저장 범위를 확인한다.
    public static BigDecimal rate(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw new BusinessException(INVALID_REQUEST);
        }
        if (rate.scale() < -10 || rate.scale() > 128 || rate.precision() > 128) {
            throw new BusinessException(INVALID_REQUEST);
        }
        BigDecimal rounded = rate.setScale(12, RoundingMode.HALF_UP);
        if (rounded.signum() <= 0 || rounded.precision() > 22) {
            throw new BusinessException(INVALID_REQUEST);
        }
        return rounded;
    }
}
