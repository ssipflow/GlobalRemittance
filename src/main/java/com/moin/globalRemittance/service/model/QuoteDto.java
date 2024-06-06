package com.moin.globalRemittance.service.model;

import com.moin.globalRemittance.dto.IQuoteDto;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

@Builder
@Getter
@Setter
@ToString
public class QuoteDto implements IQuoteDto {

    private Integer quoteId;
    private Double exchangeRate;
    private Double targetAmount;
    private String expireTime;
    private String targetCurrency;
    private BigDecimal amount;

}
