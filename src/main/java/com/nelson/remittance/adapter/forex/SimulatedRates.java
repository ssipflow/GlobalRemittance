package com.nelson.remittance.adapter.forex;

import static com.nelson.remittance.domain.BusinessException.Code.RATE_UNAVAILABLE;

import com.nelson.remittance.application.port.RateProvider;
import com.nelson.remittance.domain.BusinessException;
import com.nelson.remittance.domain.RateSnapshot;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 개발·시연·검증용 모의 공급처: 설정 환율에 지연 또는 장애를 주입해 신선도 정책의 동작을 재현한다. */
public final class SimulatedRates implements RateProvider {
    private static final Logger LOG = LoggerFactory.getLogger(SimulatedRates.class);
    private final RateProvider delegate;
    private final Clock clock;
    private final Duration age;
    private final boolean unavailable;

    public SimulatedRates(RateProvider delegate, Clock clock, Duration age, boolean unavailable) {
        this.delegate = delegate;
        this.clock = clock;
        this.age = age;
        this.unavailable = unavailable;
        LOG.warn(
                "모의 환율 공급처 사용 중: 운영에서 사용하지 마세요 ageSeconds={} unavailable={}",
                age.toSeconds(),
                unavailable);
    }

    @Override
    // 장애 모드면 통화 검증 전에 실패시키고, 아니면 설정 환율의 기준 시각만 age만큼 과거로 돌린다.
    public RateSnapshot rate(String currency) {
        if (unavailable) {
            throw new BusinessException(RATE_UNAVAILABLE);
        }
        var base = delegate.rate(currency);
        return new RateSnapshot(base.toKrw(), clock.instant().minus(age));
    }
}
