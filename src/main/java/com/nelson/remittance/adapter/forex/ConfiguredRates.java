package com.nelson.remittance.adapter.forex;

import static com.nelson.remittance.domain.BusinessException.Code.INVALID_REQUEST;

import com.nelson.remittance.application.port.RateProvider;
import com.nelson.remittance.domain.BusinessException;
import com.nelson.remittance.domain.QuotePolicy;
import com.nelson.remittance.domain.RateSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;

/** 실시간 제공처 미지정: 설정 환율을 사용하는 재현 가능한 외부 어댑터다. */
public final class ConfiguredRates implements RateProvider {
    private final Map<String, BigDecimal> rates;
    private final Clock clock;

    public ConfiguredRates(BigDecimal usd, BigDecimal jpy, Clock clock) {
        rates = Map.of("USD", QuotePolicy.rate(usd), "JPY", QuotePolicy.rate(jpy));
        this.clock = clock;
    }

    @Override
    // 대상 통화를 허용 목록에서 조회하며 미지원 통화는 거부한다. 설정값은 늘 현재 유효하므로 기준 시각은 지금이다.
    public RateSnapshot rate(String currency) {
        BigDecimal rate = rates.get(currency);
        if (rate == null) {
            throw new BusinessException(INVALID_REQUEST);
        }
        return new RateSnapshot(rate, clock.instant());
    }
}
