package com.moin.globalRemittance.dto;

import java.util.List;

public interface IHistoryDto {
    String getUserId();
    String getName();
    Integer getTodayTransferCount();
    Double getTodayTransferUsdAmount();
    List<IRemittanceHistoryDto> getHistory();
}
