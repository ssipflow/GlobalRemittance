package com.moin.globalRemittance.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "remittances")
public class Remittance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, referencedColumnName = "id")
    private User user;

    @Column(name = "source_amount", nullable = false, precision = 22, scale = 12)
    private BigDecimal sourceAmount;

    @Column(name = "target_amount", nullable = false, precision = 22, scale = 12)
    private BigDecimal targetAmount;

    @Column(name = "target_currency", nullable = false, length = 3)
    private String targetCurrency;

    @Column(name = "exchange_rate", nullable = false, precision = 22, scale = 12)
    private BigDecimal exchangeRate;

    @Column(name = "fixed_fee", nullable = false, precision = 22, scale = 12)
    private BigDecimal fixedFee;

    @Column(name = "rated_fee", nullable = false, precision = 22, scale = 12)
    private BigDecimal ratedFee;

    @Column(name = "fee_ratio", nullable = false, precision = 22, scale = 12)
    private BigDecimal feeRatio;

    @Column(name = "usd_amount", nullable = false, precision = 22, scale = 12)
    private BigDecimal usdAmount;

    @Column(name = "usd_exchange_rate", nullable = false, precision = 22, scale = 12)
    private BigDecimal usdExchangeRate;

    @Column(name = "expire_time", nullable = false)
    private Instant expireTime;

    @Column(name = "requested_time")
    private Instant requestedTime;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

}