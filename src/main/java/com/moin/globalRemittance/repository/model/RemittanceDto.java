package com.moin.globalRemittance.repository.model;

import com.moin.globalRemittance.dto.IRemittanceDto;
import com.moin.globalRemittance.entities.Remittance;
import com.moin.globalRemittance.entities.User;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Builder
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class RemittanceDto implements IRemittanceDto {
    private Integer id;
    private Integer userId;
    private BigDecimal sourceAmount;;
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

    public Remittance toEntity() {
        User user = new User();
        user.setId(this.userId);

        Remittance entity = new Remittance();
        entity.setUser(user);
        entity.setSourceAmount(this.sourceAmount);
        entity.setTargetAmount(this.targetAmount);
        entity.setTargetCurrency(this.targetCurrency);
        entity.setExchangeRate(this.exchangeRate);
        entity.setFixedFee(this.fixedFee);
        entity.setRatedFee(this.ratedFee);
        entity.setFeeRatio(this.feeRatio);
        entity.setUsdAmount(this.usdAmount);
        entity.setUsdExchangeRate(this.usdExchangeRate);
        entity.setExpireTime(this.expireTime);
        entity.setRequestedTime(this.requestedTime);
        entity.setUpdatedAt(this.updatedAt);
        entity.setCreatedAt(this.createdAt);

        return entity;
    }
}
