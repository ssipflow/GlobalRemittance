package com.moin.remittance.bootstrap;

import com.moin.remittance.adapter.forex.ConfiguredRates;
import com.moin.remittance.adapter.security.AesIdentityCipher;
import com.moin.remittance.adapter.security.JwtTokens;
import com.moin.remittance.application.Ports;
import com.moin.remittance.application.TransferUseCases;
import com.moin.remittance.application.UserUseCases;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class ApplicationConfiguration {
    @Bean
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
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Ports.Passwords passwords() {
        var encoder = new BCryptPasswordEncoder(12);
        return new Ports.Passwords() {
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
    Ports.IdentityCipher cipher(@Value("${remittance.aes-key}") String key) {
        return new AesIdentityCipher(key);
    }

    @Bean
    Ports.Tokens tokens(@Value("${remittance.jwt-secret}") String secret, Clock clock) {
        return new JwtTokens(secret, clock);
    }

    @Bean
    Ports.Rates rates(
            @Value("${remittance.usd-to-krw}") BigDecimal usd,
            @Value("${remittance.jpy-to-krw}") BigDecimal jpy) {
        return new ConfiguredRates(usd, jpy);
    }

    @Bean
    UserUseCases users(
            Ports.Accounts accounts,
            Ports.Passwords passwords,
            Ports.IdentityCipher cipher,
            Ports.Tokens tokens,
            Ports.Work work,
            Clock clock) {
        return new UserUseCases(accounts, passwords, cipher, tokens, work, clock);
    }

    @Bean
    TransferUseCases transfers(
            Ports.Accounts accounts,
            Ports.Quotes quotes,
            Ports.Rates rates,
            Ports.Work work,
            Clock clock) {
        return new TransferUseCases(accounts, quotes, rates, work, clock);
    }
}
