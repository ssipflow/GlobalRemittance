package com.moin.globalRemittance.service.model;

import com.moin.globalRemittance.dto.IQuoteBaseDto;
import com.moin.globalRemittance.dto.IRemittanceDto;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Builder
@Getter
@Setter
@ToString
public class QuoteBaseDto implements IQuoteBaseDto {

    private Integer quoteId;
    private Double exchangeRate;
    private Double targetAmount;
    private String expireTime;
    private String targetCurrency;

    public static IQuoteBaseDto fromRemittance(IRemittanceDto param) {
        ZonedDateTime zonedDateTime = param.getExpireTime().atZone(ZoneId.systemDefault());
        return QuoteBaseDto.builder()
                .quoteId(param.getId())
                .exchangeRate(param.getExchangeRate().doubleValue())
                .targetAmount(param.getTargetAmount().setScale(2, RoundingMode.HALF_UP).doubleValue())
                .targetCurrency(param.getTargetCurrency())
                .expireTime(zonedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .build();
    }
}
