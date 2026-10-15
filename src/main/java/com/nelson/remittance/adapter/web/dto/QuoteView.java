package com.nelson.remittance.adapter.web.dto;

import com.nelson.remittance.domain.quote.Quote;
import java.math.BigDecimal;

// 영속 모델 없이 공개 견적 필드와 KST 만료 시각을 전달한다.
public record QuoteView(
        String quoteId, BigDecimal exchangeRate, String expireTime, BigDecimal targetAmount) {
    public static QuoteView of(Quote quote) {
        var c = quote.calculation();
        return new QuoteView(
                Long.toString(quote.id()),
                c.exchangeRate(),
                ApiTime.format(quote.expireTime()),
                c.targetAmount());
    }
}
