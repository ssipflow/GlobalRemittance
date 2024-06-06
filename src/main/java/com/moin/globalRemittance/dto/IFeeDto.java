package com.moin.globalRemittance.dto;

import java.math.BigDecimal;

public interface IFeeDto {
    BigDecimal getFixedFee();
    BigDecimal getRatedFee();
    BigDecimal getFeeRatio();
    BigDecimal getCalculatedFee();
}
