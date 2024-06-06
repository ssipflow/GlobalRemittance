package com.moin.globalRemittance.service;

import com.moin.globalRemittance.dto.*;
import com.moin.globalRemittance.exception.RemittanceException;
import com.moin.globalRemittance.repository.custom.RemittanceRepository;
import com.moin.globalRemittance.service.model.*;
import com.moin.globalRemittance.utils.ForexUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final RemittanceRepository remittanceRepository;

    private final ForexUtil forexUtil;

    public IQuoteBaseDto createQuote(Integer userid, IQuoteDto param) {
        List<IForexDto> forexList = forexUtil.getForexRateList(param.getTargetCurrency());
        IForexDto targetForex = forexList.stream()
                .filter(f -> param.getTargetCurrency().equalsIgnoreCase(f.getCurrencyCode()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("targetForex data not found"));

        IForexDto usdForex = forexList.stream()
                .filter(f -> "USD".equalsIgnoreCase(f.getCurrencyCode()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("usdForex data not found"));

        IFeeDto fee = FeeDto.getFeeData(param.getTargetCurrency(), param.getAmount());

        RemittanceDto remittance = RemittanceDto.builder()
                .userId(userid)
                .sourceAmount(param.getAmount())
                .targetCurrency(param.getTargetCurrency())
                .exchangeRate(targetForex.getExchangeRate())
                .fixedFee(fee.getFixedFee())
                .ratedFee(fee.getRatedFee())
                .feeRatio(fee.getFeeRatio())
                .usdExchangeRate(usdForex.getExchangeRate())
                .expireTime(Instant.now().plusMillis(10 * 60 * 1000))
                .build();
        remittance.calculateRemittance();

        if (!remittance.isPositiveAmount()) {
            throw new RemittanceException("NEGATIVE_NUMBER");
        }
        if (!remittance.isPositiveTargetAmount()) {
            throw new RemittanceException("NEGATIVE_NUMBER");
        }

        Integer remittanceId = remittanceRepository.createRemittance(remittance);
        remittance.setId(remittanceId);

        return QuoteBaseDto.fromRemittance(remittance);
    }

    public void requestRemittance(IUserDto user, Integer quoteId) {
        IRemittanceDto remittanceFromDB = remittanceRepository.getWaitingRemittanceById(quoteId);
        if (remittanceFromDB == null) {
            throw new RemittanceException("UNKNOWN_ERROR");
        }

        RemittanceDto remittance = RemittanceDto.fromIRemittanceDto(remittanceFromDB);
        if (!remittance.isValidUser(user.getId())) {
            throw new RemittanceException("UNKNOWN_ERROR");
        }
        if (remittance.isExpired()) {
            throw new RemittanceException("QUOTE_EXPIRED");
        }

        List<IRemittanceDto> dailyRequested = remittanceRepository.getDailyRemittanceListByUser(user.getId());
        BigDecimal dailyRequestedUsdAmount = dailyRequested.stream()
                .map(IRemittanceDto::getUsdAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(remittance.getUsdAmount());
        if (isExceedLimit(dailyRequestedUsdAmount, user)) {
            throw new RemittanceException("LIMIT_EXCESS");
        }

        remittanceRepository.updateRequestedTime(quoteId);
    }

    public IHistoryDto getUserRemittanceHistory(IUserDto user) {
        List<IRemittanceDto> remittanceList = remittanceRepository.getDailyRemittanceListByUser(user.getId());

        List<IRemittanceHistoryDto> remittanceHistoryList = remittanceList.stream()
                .map(RemittanceHistoryDto::fromIRemittance)
                .toList();

        BigDecimal dailyUsdAmount = remittanceList.stream()
                .map(IRemittanceDto::getUsdAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(Currency.getInstance("USD").getDefaultFractionDigits(), RoundingMode.HALF_UP);

        return HistoryDto.builder()
                .userId(user.getUserId())
                .name(user.getName())
                .todayTransferCount(remittanceList.size())
                .todayTransferUsdAmount(dailyUsdAmount.doubleValue())
                .history(remittanceHistoryList)
                .build();
    }

    private Boolean isExceedLimit(BigDecimal usdAmount, IUserDto user) {
        switch (user.getIdType()) {
            case "REG_NO" -> {
                return !(usdAmount.compareTo(BigDecimal.valueOf(1000)) < 0);
            }
            case "BUSINESS_NO" -> {
                return !(usdAmount.compareTo(BigDecimal.valueOf(5000)) < 0);
            }
            default -> {
                return false;
            }
        }
    }
}
