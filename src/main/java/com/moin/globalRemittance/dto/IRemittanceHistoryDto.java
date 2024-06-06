package com.moin.globalRemittance.dto;

public interface IRemittanceHistoryDto {
    Long getSourceAmount();
    Double getFee();
    Double getUsdExchangeRate();
    Double getUsdAmount();
    String getTargetCurrency();
    Double getExchangeRate();
    Double getTargetAmount();
    String getRequestedDate();

}
