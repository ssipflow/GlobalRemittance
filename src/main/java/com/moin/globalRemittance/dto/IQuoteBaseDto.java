package com.moin.globalRemittance.dto;

public interface IQuoteBaseDto {
    Integer getQuoteId();
    Double getExchangeRate();
    Double getTargetAmount();
    String getTargetCurrency();
    String getExpireTime();
    String toString();

}
