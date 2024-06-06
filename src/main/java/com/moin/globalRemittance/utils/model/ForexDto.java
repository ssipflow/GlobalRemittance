package com.moin.globalRemittance.utils.model;

import com.moin.globalRemittance.dto.IForexDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Getter
@Setter
@ToString
public class ForexDto implements IForexDto {

    private String currencyCode;
    private BigDecimal basePrice;
    private BigDecimal currencyUnit;
    private BigDecimal exchangeRate;

    public void calculateExchangeRate() {
        this.exchangeRate = basePrice.setScale(12, RoundingMode.HALF_UP)
                .divide(currencyUnit, RoundingMode.HALF_UP)
                .setScale(12, RoundingMode.HALF_UP);
    }
}
