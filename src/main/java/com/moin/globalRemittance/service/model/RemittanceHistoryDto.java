package com.moin.globalRemittance.service.model;

import com.moin.globalRemittance.dto.IRemittanceDto;
import com.moin.globalRemittance.dto.IRemittanceHistoryDto;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Currency;

@Builder
@Getter
@Setter
@ToString
public class RemittanceHistoryDto implements IRemittanceHistoryDto {

    private Long sourceAmount;
    private Double fee;
    private Double usdExchangeRate;
    private Double usdAmount;
    private String targetCurrency;
    private Double exchangeRate;
    private Double targetAmount;
    private String requestedDate;

    public static IRemittanceHistoryDto fromIRemittance(IRemittanceDto dto) {
        BigDecimal fee = dto.getFixedFee().add(dto.getRatedFee())
                .setScale(12, RoundingMode.HALF_UP);
        ZonedDateTime zonedDateTime = dto.getRequestedTime()
                .atZone(ZoneId.systemDefault());

        int targetDigits = Currency.getInstance(dto.getTargetCurrency()).getDefaultFractionDigits();
        int usdDigits = Currency.getInstance("USD").getDefaultFractionDigits();

        return RemittanceHistoryDto.builder()
                .sourceAmount(dto.getSourceAmount().longValue())
                .fee(fee.doubleValue())
                .usdExchangeRate(dto.getUsdExchangeRate().doubleValue())
                .usdAmount(dto.getUsdAmount().setScale(usdDigits, RoundingMode.HALF_UP).doubleValue())
                .targetCurrency(dto.getTargetCurrency())
                .exchangeRate(dto.getExchangeRate().doubleValue())
                .targetAmount(dto.getTargetAmount().setScale(targetDigits, RoundingMode.HALF_UP).doubleValue())
                .requestedDate(zonedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .build();
    }

}
