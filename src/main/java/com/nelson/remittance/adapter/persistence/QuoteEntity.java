package com.nelson.remittance.adapter.persistence;

import com.nelson.remittance.domain.Quote;
import com.nelson.remittance.domain.QuotePolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "remittances",
        indexes =
                @Index(
                        name = "idx_remittances_user_requested",
                        columnList = "user_id,requested_time"))
public class QuoteEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    UserEntity user;

    @Column(name = "source_amount", nullable = false, precision = 22, scale = 12)
    BigDecimal sourceAmount;

    @Column(name = "target_amount", nullable = false, precision = 22, scale = 12)
    BigDecimal targetAmount;

    @Column(name = "target_currency", nullable = false, length = 3)
    String targetCurrency;

    @Column(name = "exchange_rate", nullable = false, precision = 22, scale = 12)
    BigDecimal exchangeRate;

    @Column(name = "fixed_fee", nullable = false, precision = 22, scale = 12)
    BigDecimal fixedFee;

    @Column(name = "rated_fee", nullable = false, precision = 22, scale = 12)
    BigDecimal ratedFee;

    @Column(name = "fee_ratio", nullable = false, precision = 22, scale = 12)
    BigDecimal feeRatio;

    @Column(name = "usd_amount", nullable = false, precision = 22, scale = 12)
    BigDecimal usdAmount;

    @Column(name = "usd_exchange_rate", nullable = false, precision = 22, scale = 12)
    BigDecimal usdExchangeRate;

    @Column(name = "expire_time", nullable = false)
    Instant expireTime;

    @Column(name = "requested_time")
    Instant requestedTime;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected QuoteEntity() {}

    QuoteEntity(Quote quote, UserEntity user) {
        this.user = user;
        var c = quote.calculation();
        sourceAmount = c.sourceAmount();
        targetAmount = c.targetAmount();
        targetCurrency = c.targetCurrency();
        exchangeRate = c.exchangeRate();
        fixedFee = c.fixedFee();
        ratedFee = c.ratedFee();
        feeRatio = c.feeRatio();
        usdAmount = c.usdAmount();
        usdExchangeRate = c.usdExchangeRate();
        expireTime = quote.expireTime();
        requestedTime = quote.requestedTime();
        createdAt = quote.createdAt();
        updatedAt = quote.updatedAt();
    }

    Quote toDomain() {
        // LAZY 소유자 프록시의 필드 직접 접근은 null일 수 있다. 식별자 getter를 통해 읽는다.
        return new Quote(
                id,
                user.getId(),
                new QuotePolicy.Calculation(
                        QuotePolicy.money(sourceAmount, "KRW"),
                        targetCurrency,
                        exchangeRate,
                        QuotePolicy.money(fixedFee, "KRW"),
                        QuotePolicy.money(ratedFee, "KRW"),
                        feeRatio,
                        QuotePolicy.money(targetAmount, targetCurrency),
                        usdExchangeRate,
                        QuotePolicy.money(usdAmount, "USD")),
                expireTime,
                requestedTime,
                createdAt,
                updatedAt);
    }
}
