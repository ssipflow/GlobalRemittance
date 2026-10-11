package com.nelson.remittance.adapter.forex;

import static com.nelson.remittance.domain.BusinessException.Code.INVALID_REQUEST;

import com.nelson.remittance.application.Ports;
import com.nelson.remittance.domain.BusinessException;
import com.nelson.remittance.domain.QuotePolicy;
import java.math.BigDecimal;
import java.util.Map;

/** 실시간 제공처 미지정: 설정 환율을 사용하는 재현 가능한 외부 어댑터다. */
public final class ConfiguredRates implements Ports.Rates {
    private final Map<String, BigDecimal> rates;

    public ConfiguredRates(BigDecimal usd, BigDecimal jpy) {
        rates = Map.of("USD", QuotePolicy.rate(usd), "JPY", QuotePolicy.rate(jpy));
    }

    @Override
    public BigDecimal toKrw(String currency) {
        BigDecimal rate = rates.get(currency);
        if (rate == null) {
            throw new BusinessException(INVALID_REQUEST);
        }
        return rate;
    }
}
