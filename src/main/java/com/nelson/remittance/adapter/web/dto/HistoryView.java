package com.nelson.remittance.adapter.web.dto;

import com.nelson.remittance.domain.Quote;
import java.math.BigDecimal;

// 접수 거래의 금액·환율·수수료 감사 값과 KST 접수 날짜를 표시한다.
public record HistoryView(
        BigDecimal sourceAmount,
        BigDecimal fee,
        BigDecimal usdExchangeRate,
        BigDecimal usdAmount,
        String targetCurrency,
        BigDecimal exchangeRate,
        BigDecimal targetAmount,
        String requestedDate) {
    public static HistoryView of(Quote quote) {
        var c = quote.calculation();
        return new HistoryView(
                c.sourceAmount(),
                c.fee(),
                c.usdExchangeRate(),
                c.usdAmount(),
                c.targetCurrency(),
                c.exchangeRate(),
                c.targetAmount(),
                ApiTime.format(quote.requestedTime()));
    }
}
