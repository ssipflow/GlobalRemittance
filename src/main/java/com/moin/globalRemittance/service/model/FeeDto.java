package com.moin.globalRemittance.service.model;

import com.moin.globalRemittance.dto.IFeeDto;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Getter
@Setter
@ToString
@NoArgsConstructor
public class FeeDto implements IFeeDto {
    private BigDecimal fixedFee;
    private BigDecimal ratedFee;
    private BigDecimal feeRatio;
    private BigDecimal calculatedFee;

    public static IFeeDto getFeeData(String targetCurrency, BigDecimal amount) {
        FeeDto feeDto = new FeeDto();
        switch (targetCurrency.toUpperCase()) {
            case "USD" -> {
                if (amount.compareTo(BigDecimal.valueOf(1000000)) > 0) {
                    feeDto.setFixedFee(
                            BigDecimal.valueOf(3000).setScale(12, RoundingMode.HALF_UP));
                    feeDto.setFeeRatio(
                            BigDecimal.valueOf(0.001).setScale(12, RoundingMode.HALF_UP));
                } else {
                    feeDto.setFixedFee(
                            BigDecimal.valueOf(1000).setScale(12, RoundingMode.HALF_UP));
                    feeDto.setFeeRatio(
                            BigDecimal.valueOf(0.002).setScale(12, RoundingMode.HALF_UP));
                }
            }
            case "JPY" -> {
                feeDto.setFixedFee(
                        BigDecimal.valueOf(3000).setScale(12, RoundingMode.HALF_UP));
                feeDto.setFeeRatio(
                        BigDecimal.valueOf(0.005).setScale(12, RoundingMode.HALF_UP));
            }
            default -> {
            }
        }

        BigDecimal ratedFee = amount.setScale(12, RoundingMode.HALF_UP)
                .multiply(feeDto.feeRatio).setScale(12, RoundingMode.HALF_UP);
        BigDecimal calculatedFee = ratedFee.add(feeDto.fixedFee).setScale(12, RoundingMode.HALF_UP);

        feeDto.setRatedFee(ratedFee);
        feeDto.setCalculatedFee(calculatedFee);

        return feeDto;
    }
}
