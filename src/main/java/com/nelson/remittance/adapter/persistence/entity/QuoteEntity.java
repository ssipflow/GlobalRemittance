package com.nelson.remittance.adapter.persistence.entity;

import com.nelson.remittance.domain.quote.Quote;
import com.nelson.remittance.domain.quote.QuotePolicy;
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
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "remittances",
        indexes =
                @Index(
                        name = "idx_remittances_user_requested",
                        columnList = "user_id,requested_time"),
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_remittances_user_idempotency_key",
                        columnNames = {"user_id", "idempotency_key"}))
// 견적별 환율·수수료·금액을 감사 자료로 저장하고 접수 시각으로 미접수 거래를 구분한다.
// 멱등성 키는 접수된 행에만 있으며 (사용자, 키) 유일 제약이 한 키가 한 견적에만 묶이게 한다.
// 키가 없는 행은 NULL이라 유일 제약에서 서로 충돌하지 않는다.
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

    @Column(name = "idempotency_key", length = 64)
    String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected QuoteEntity() {}

    public QuoteEntity(Quote quote, UserEntity user) {
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
        idempotencyKey = quote.idempotencyKey();
        createdAt = quote.createdAt();
        updatedAt = quote.updatedAt();
    }

    // 저장 자릿수를 통화 자릿수로 복원하며 LAZY 소유자 식별자는 프록시 getter로 읽는다.
    public Quote toDomain() {
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
                idempotencyKey,
                createdAt,
                updatedAt);
    }

    // 접수 상태 전이만 허용한다. 견적 시 확정한 계산 필드는 바뀌지 않는다.
    public void markRequested(Instant requestedTime, String idempotencyKey, Instant updatedAt) {
        this.requestedTime = requestedTime;
        this.idempotencyKey = idempotencyKey;
        this.updatedAt = updatedAt;
    }
}
