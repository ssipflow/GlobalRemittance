package com.moin.globalRemittance.dto;

import java.math.BigDecimal;

public interface IQuoteDto extends IQuoteBaseDto {
    BigDecimal getAmount();

}
