package com.nelson.remittance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nelson.remittance.adapter.forex.ConfiguredRates;
import com.nelson.remittance.application.TransferUseCases;
import com.nelson.remittance.application.port.AccountRepository;
import com.nelson.remittance.application.port.QuoteRepository;
import com.nelson.remittance.application.port.RateProvider;
import com.nelson.remittance.domain.BusinessException;
import com.nelson.remittance.domain.BusinessException.Code;
import com.nelson.remittance.domain.RateSnapshot;
import com.nelson.remittance.domain.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 환율 신선도 정책을 Spring 없이 고정 시계와 대역으로 검증한다.
class RatePolicyTest {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final Duration MAX = Duration.ofSeconds(60);
    static final BigDecimal AMOUNT = new BigDecimal("100000");

    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    AccountRepository accounts = mock(AccountRepository.class);
    QuoteRepository quotes = mock(QuoteRepository.class);
    RateProvider rates = mock(RateProvider.class);
    TransferUseCases transfers;

    @BeforeEach
    void setUp() {
        when(accounts.findUser(anyLong())).thenReturn(Optional.of(mock(User.class)));
        when(quotes.saveQuote(any())).thenAnswer(call -> call.getArgument(0));
        transfers = new TransferUseCases(accounts, quotes, rates, clock, MAX);
    }

    RateSnapshot at(String rate, Instant asOf) {
        return new RateSnapshot(new BigDecimal(rate), asOf);
    }

    void assertCode(Code code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).code());
    }

    @Test
    // 경계는 신선이고 1ms만 넘어도 거부한다.
    void boundaryAgeIsFreshAndBeyondIsStale() {
        when(rates.rate("USD")).thenReturn(at("1000", NOW.minus(MAX)));
        assertDoesNotThrow(() -> transfers.quote(1, AMOUNT, "USD"));

        when(rates.rate("USD")).thenReturn(at("1000", NOW.minus(MAX).minusMillis(1)));
        assertCode(Code.RATE_STALE, () -> transfers.quote(1, AMOUNT, "USD"));
    }

    @Test
    // 미래 기준 시각도 허용 범위 안이면 통과하고 넘으면 시계 어긋남으로 거부한다.
    void futureAsOfIsBoundedToo() {
        when(rates.rate("USD")).thenReturn(at("1000", NOW.plus(MAX)));
        assertDoesNotThrow(() -> transfers.quote(1, AMOUNT, "USD"));

        when(rates.rate("USD")).thenReturn(at("1000", NOW.plus(MAX).plusMillis(1)));
        assertCode(Code.RATE_STALE, () -> transfers.quote(1, AMOUNT, "USD"));
    }

    @Test
    void isFreshIsSymmetric() {
        var snapshot = at("1", NOW);
        assertTrue(snapshot.isFresh(NOW.plus(MAX), MAX));
        assertTrue(snapshot.isFresh(NOW.minus(MAX), MAX));
        assertFalse(snapshot.isFresh(NOW.plus(MAX).plusNanos(1), MAX));
        assertFalse(snapshot.isFresh(NOW.minus(MAX).minusNanos(1), MAX));
    }

    @Test
    // 대상 통화나 USD 중 하나만 오래돼도 거부하며 저장하지 않는다.
    void eitherStaleRejectsAndNothingIsSaved() {
        Instant old = NOW.minus(MAX).minusSeconds(1);
        when(rates.rate("JPY")).thenReturn(at("10", old));
        when(rates.rate("USD")).thenReturn(at("1000", NOW));
        assertCode(Code.RATE_STALE, () -> transfers.quote(1, AMOUNT, "JPY"));

        when(rates.rate("JPY")).thenReturn(at("10", NOW));
        when(rates.rate("USD")).thenReturn(at("1000", old));
        assertCode(Code.RATE_STALE, () -> transfers.quote(1, AMOUNT, "JPY"));

        verify(quotes, never()).saveQuote(any());
    }

    @Test
    // 대상이 USD이면 한 번만 조회해 같은 스냅샷을 쓴다.
    void usdTargetFetchesOnce() {
        when(rates.rate("USD")).thenReturn(at("1000", NOW));
        transfers.quote(1, AMOUNT, "USD");
        verify(rates, times(1)).rate("USD");
    }

    @Test
    // 공급 실패 코드는 그대로 전달되고 견적은 저장되지 않는다.
    void unavailablePropagates() {
        when(rates.rate("JPY")).thenThrow(new BusinessException(Code.RATE_UNAVAILABLE));
        assertCode(Code.RATE_UNAVAILABLE, () -> transfers.quote(1, AMOUNT, "JPY"));
        verify(quotes, never()).saveQuote(any());
    }

    @Test
    // 공급처가 던진 다른 예외는 삼키지 않는다.
    void otherExceptionsAreNotSwallowed() {
        when(rates.rate("USD")).thenThrow(new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class, () -> transfers.quote(1, AMOUNT, "USD"));
    }

    @Test
    // 미지원 통화는 기존대로 INVALID_REQUEST다.
    void unsupportedCurrencyStaysInvalidRequest() {
        var configured =
                new TransferUseCases(
                        accounts,
                        quotes,
                        new ConfiguredRates(new BigDecimal("1000"), new BigDecimal("10"), clock),
                        clock,
                        MAX);
        assertCode(Code.INVALID_REQUEST, () -> configured.quote(1, AMOUNT, "EUR"));
    }

    @Test
    // 사용자가 없으면 환율을 조회하기 전에 UNAUTHORIZED다.
    void unknownUserFailsBeforeRateLookup() {
        when(accounts.findUser(anyLong())).thenReturn(Optional.empty());
        assertCode(Code.UNAUTHORIZED, () -> transfers.quote(9, AMOUNT, "USD"));
        verify(rates, never()).rate(any());
    }
}
