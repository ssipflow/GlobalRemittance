package com.moin.globalRemittance.controller.model.response;

import com.moin.globalRemittance.dto.IQuoteBaseDto;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class QuoteResponse extends BaseResponse {
    private IQuoteBaseDto quote;
}
