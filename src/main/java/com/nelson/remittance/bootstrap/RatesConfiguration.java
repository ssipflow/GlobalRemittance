package com.nelson.remittance.bootstrap;

import static com.nelson.remittance.bootstrap.ConfigValues.bool;
import static com.nelson.remittance.bootstrap.ConfigValues.integer;

import com.nelson.remittance.adapter.forex.ConfiguredRates;
import com.nelson.remittance.adapter.forex.SimulatedRates;
import com.nelson.remittance.application.RateFreshnessPolicy;
import com.nelson.remittance.application.port.RateProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RatesConfiguration {
    @Bean
    // 환율 공급처를 설정으로 고른다. 설정값이 잘못되면 요청 시점이 아니라 기동 시점에 실패시킨다.
    RateProvider rates(
            @Value("${remittance.usd-to-krw}") BigDecimal usd,
            @Value("${remittance.jpy-to-krw}") BigDecimal jpy,
            @Value("${remittance.rates.source:configured}") String source,
            @Value("${remittance.rates.simulated.age-seconds:0}") String ageSeconds,
            @Value("${remittance.rates.simulated.unavailable:false}") String unavailable,
            Clock clock) {
        var configured = new ConfiguredRates(usd, jpy, clock);
        return switch (source) {
            case "configured" -> configured;
            case "simulated" ->
                    new SimulatedRates(
                            configured,
                            clock,
                            Duration.ofSeconds(
                                    integer(
                                            "remittance.rates.simulated.age-seconds",
                                            ageSeconds,
                                            0,
                                            Integer.MAX_VALUE)),
                            bool("remittance.rates.simulated.unavailable", unavailable));
            default ->
                    throw new IllegalArgumentException(
                            "remittance.rates.source는 configured 또는 simulated여야 합니다.");
        };
    }

    @Bean
    // 환율 허용 지연은 1~3600초다. 잘못된 값은 요청 시점이 아니라 기동 시점에 설정 키와 함께 거부한다.
    RateFreshnessPolicy rateFreshnessPolicy(
            @Value("${remittance.rates.max-age-seconds:60}") String maxAgeSeconds) {
        return new RateFreshnessPolicy(
                Duration.ofSeconds(
                        integer("remittance.rates.max-age-seconds", maxAgeSeconds, 1, 3600)));
    }
}
