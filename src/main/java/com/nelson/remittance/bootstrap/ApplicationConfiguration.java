package com.nelson.remittance.bootstrap;

import com.nelson.remittance.adapter.forex.ConfiguredRates;
import com.nelson.remittance.adapter.forex.SimulatedRates;
import com.nelson.remittance.adapter.persistence.RollbackSafeTransactionManager;
import com.nelson.remittance.adapter.security.AesIdentityCipher;
import com.nelson.remittance.adapter.security.JwtTokens;
import com.nelson.remittance.application.RateFreshnessPolicy;
import com.nelson.remittance.application.port.IdentityCipher;
import com.nelson.remittance.application.port.PasswordHasher;
import com.nelson.remittance.application.port.RateProvider;
import com.nelson.remittance.application.port.TokenProvider;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
// 외부 구현을 포트에 연결하고 시간·환율·키 설정을 시작 시점에 구성한다.
public class ApplicationConfiguration {
    @Bean
    // 문자열 식별자와 숫자 금액의 자동 변환을 차단하여 JSON 타입 계약을 보존한다.
    org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
            strictJson() {
        return builder ->
                builder.postConfigurer(
                        mapper -> {
                            // JSON 문자열 식별자와 숫자 금액을 서로 강제 변환하지 않아 API 타입 계약을 지킨다.
                            var textual =
                                    mapper.coercionConfigFor(
                                            com.fasterxml.jackson.databind.type.LogicalType
                                                    .Textual);
                            for (var shape :
                                    new com.fasterxml.jackson.databind.cfg.CoercionInputShape[] {
                                        com.fasterxml.jackson.databind.cfg.CoercionInputShape
                                                .Integer,
                                        com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                                        com.fasterxml.jackson.databind.cfg.CoercionInputShape
                                                .Boolean
                                    }) {
                                textual.setCoercion(
                                        shape,
                                        com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
                            }
                            mapper.coercionConfigFor(
                                            com.fasterxml.jackson.databind.type.LogicalType.Float)
                                    .setCoercion(
                                            com.fasterxml.jackson.databind.cfg.CoercionInputShape
                                                    .String,
                                            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
                        });
    }

    @Bean
    // 업무 시간은 하나의 UTC Clock으로 공급하고 테스트에서는 고정 시간으로 바꾼다.
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    // BCrypt의 비용과 솔트를 적용하며 비밀번호 원문을 저장 포트에 전달하지 않는다.
    public PasswordHasher passwords() {
        var encoder = new BCryptPasswordEncoder(12);
        return new PasswordHasher() {
            @Override
            public String hash(String raw) {
                return encoder.encode(raw);
            }

            @Override
            public boolean matches(String raw, String hash) {
                return encoder.matches(raw, hash);
            }
        };
    }

    @Bean
    // 필수 환경 키를 읽어 AES 키 길이를 시작 시점에 확인한다.
    IdentityCipher cipher(@Value("${remittance.aes-key}") String key) {
        return new AesIdentityCipher(key);
    }

    @Bean
    // JWT 키와 공통 Clock을 연결하고 잘못된 키 설정은 시작 단계에서 실패시킨다.
    TokenProvider tokens(@Value("${remittance.jwt-secret}") String secret, Clock clock) {
        return new JwtTokens(secret, clock);
    }

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
    // 롤백 실패가 원래 예외를 덮지 않는 매니저를 두면 Boot 기본 JpaTransactionManager 자동 구성이 물러난다.
    // Boot의 JpaBaseConfiguration이 물러나므로 spring.transaction.* 설정(기본 타임아웃 등)을 담은
    // TransactionManagerCustomizers를 Boot와 같은 방식으로 직접 적용해 설정이 조용히 무시되지 않게 한다.
    PlatformTransactionManager transactionManager(
            EntityManagerFactory factory,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        var manager = new RollbackSafeTransactionManager(factory);
        customizers.ifAvailable(c -> c.customize(manager));
        return manager;
    }

    @Bean
    // 환율 허용 지연은 1~3600초다. 잘못된 값은 요청 시점이 아니라 기동 시점에 설정 키와 함께 거부한다.
    RateFreshnessPolicy rateFreshnessPolicy(
            @Value("${remittance.rates.max-age-seconds:60}") String maxAgeSeconds) {
        return new RateFreshnessPolicy(
                Duration.ofSeconds(
                        integer("remittance.rates.max-age-seconds", maxAgeSeconds, 1, 3600)));
    }

    // 부호·공백·소수를 허용하지 않는 ASCII 정수만 받는다. 오류 메시지에는 설정 키와 허용 범위만 담는다.
    private static int integer(String key, String value, int min, int max) {
        if (value.matches("[0-9]{1,9}")) {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        }
        throw new IllegalArgumentException(key + "는 " + min + " 이상 " + max + " 이하의 정수여야 합니다.");
    }

    private static boolean bool(String key, String value) {
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.parseBoolean(value);
        }
        throw new IllegalArgumentException(key + "는 true 또는 false여야 합니다.");
    }
}
