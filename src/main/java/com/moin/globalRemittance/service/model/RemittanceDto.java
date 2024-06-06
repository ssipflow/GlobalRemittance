package com.moin.globalRemittance.service.model;

import com.moin.globalRemittance.dto.IFeeDto;
import com.moin.globalRemittance.dto.IForexDto;
import com.moin.globalRemittance.dto.IRemittanceDto;
import com.moin.globalRemittance.dto.IUserDto;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;

@Builder
@Getter
@Setter
@ToString
public class RemittanceDto implements IRemittanceDto {
    private Integer id;
    private Integer userId;
    private BigDecimal sourceAmount;
    private BigDecimal targetAmount;
    private String targetCurrency;
    private BigDecimal exchangeRate;
    private BigDecimal fixedFee;
    private BigDecimal ratedFee;
    private BigDecimal feeRatio;
    private BigDecimal usdAmount;
    private BigDecimal usdExchangeRate;
    private Instant expireTime;
    private Instant requestedTime;
    private Instant updatedAt;
    private Instant createdAt;

    public static RemittanceDto fromIRemittanceDto(IRemittanceDto dto) {
        return RemittanceDto.builder()
                .id(dto.getId())
                .userId(dto.getUserId())
                .sourceAmount(dto.getSourceAmount())
                .targetAmount(dto.getTargetAmount())
                .targetCurrency(dto.getTargetCurrency())
                .exchangeRate(dto.getExchangeRate())
                .fixedFee(dto.getFixedFee())
                .ratedFee(dto.getRatedFee())
                .feeRatio(dto.getFeeRatio())
                .usdAmount(dto.getUsdAmount())
                .usdExchangeRate(dto.getUsdExchangeRate())
                .expireTime(dto.getExpireTime())
                .requestedTime(dto.getRequestedTime())
                .updatedAt(dto.getUpdatedAt())
                .createdAt(dto.getCreatedAt())
                .build();
    }

    public Boolean isPositiveAmount() {
        return sourceAmount.compareTo(BigDecimal.valueOf(0)) > 0;
    }

    public Boolean isPositiveTargetAmount() {
        return targetAmount.compareTo(BigDecimal.valueOf(0)) > 0;
    }

    public void calculateRemittance() {
        Currency currency = Currency.getInstance(targetCurrency);
        BigDecimal exchangeRate = this.exchangeRate
                .setScale(12, RoundingMode.HALF_UP);

        Currency usd = Currency.getInstance("USD");
        BigDecimal usdExchangeRate = this.usdExchangeRate
                .setScale(12, RoundingMode.HALF_UP);

        BigDecimal totalFee = fixedFee.add(ratedFee)
                .setScale(12, RoundingMode.HALF_UP);

        BigDecimal subtractedSourceAmount = sourceAmount.subtract(totalFee)
                .setScale(12, RoundingMode.HALF_UP);

        this.targetAmount = subtractedSourceAmount
                .divide(exchangeRate, 12, RoundingMode.HALF_UP)
                .setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);

        this.usdAmount = subtractedSourceAmount
                .divide(usdExchangeRate, 12, RoundingMode.HALF_UP)
                .setScale(usd.getDefaultFractionDigits(), RoundingMode.HALF_UP);
    }

    public Boolean isExpired() {
        Instant now = Instant.now();
        return expireTime.isBefore(now);
    }

    public Boolean isValidUser(Integer userId) {
        return userId.equals(this.userId);
    }
}
