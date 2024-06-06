package com.moin.globalRemittance.controller.model;

import com.moin.globalRemittance.dto.IQuoteDto;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

@Getter
@Setter
@ToString
public class QuoteDto implements IQuoteDto {

    private Integer quoteId;
    private Double exchangeRate;
    private Double targetAmount;
    private String expireTime;

    @Pattern(regexp = "USD|JPY", message = "targetCurrency must be either USD or JPY")
    @NotBlank(message = "targetCurrency is mandatory")
    private String targetCurrency;

    @NotNull(message = "amount is mandatory")
    @Digits(integer = 19, fraction = 0, message = "amount must be a positive integer")
    private BigDecimal amount;

}
