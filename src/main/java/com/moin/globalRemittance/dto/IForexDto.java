package com.moin.globalRemittance.dto;

import java.math.BigDecimal;

public interface IForexDto {
    String getCurrencyCode();
    BigDecimal getBasePrice();
    BigDecimal getCurrencyUnit();
    BigDecimal getExchangeRate();
    String toString();
}
