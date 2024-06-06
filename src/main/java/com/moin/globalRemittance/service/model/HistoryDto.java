package com.moin.globalRemittance.service.model;

import com.moin.globalRemittance.dto.IHistoryDto;
import com.moin.globalRemittance.dto.IRemittanceHistoryDto;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Builder
@Getter
@Setter
@ToString
public class HistoryDto implements IHistoryDto {

    private String userId;
    private String name;
    private Integer todayTransferCount;
    private Double todayTransferUsdAmount;
    private List<IRemittanceHistoryDto> history;

}
