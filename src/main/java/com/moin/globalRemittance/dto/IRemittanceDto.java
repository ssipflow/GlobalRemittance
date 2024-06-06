package com.moin.globalRemittance.dto;


import java.math.BigDecimal;
import java.time.Instant;

public interface IRemittanceDto {
    Integer getId();
    Integer getUserId();
    BigDecimal getSourceAmount();
    BigDecimal getTargetAmount();
    String getTargetCurrency();
    BigDecimal getExchangeRate();
    BigDecimal getFixedFee();
    BigDecimal getRatedFee();
    BigDecimal getFeeRatio();
    BigDecimal getUsdAmount();
    BigDecimal getUsdExchangeRate();
    Instant getExpireTime();
    Instant getRequestedTime();
    Instant getUpdatedAt();
    Instant getCreatedAt();
    String toString();
}
