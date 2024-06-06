package com.moin.globalRemittance.controller.model.response;

import com.moin.globalRemittance.dto.IRemittanceHistoryDto;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class HistoryResponse extends BaseResponse {
    private String userId;
    private String name;
    private Integer todayTransferCount;
    private Double todayTransferUsdAmount;
    private List<IRemittanceHistoryDto> history;
}
