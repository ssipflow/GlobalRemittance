package com.moin.globalRemittance.controller.model;

import com.moin.globalRemittance.dto.IQuoteBaseDto;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class QuoteBaseDto implements IQuoteBaseDto {

    private Double exchangeRate;
    private Double targetAmount;
    private String expireTime;
    private String targetCurrency;

    @NotNull(message = "quoteId is mandatory")
    private Integer quoteId;
}
