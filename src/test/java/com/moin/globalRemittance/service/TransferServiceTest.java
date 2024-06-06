package com.moin.globalRemittance.service;

import com.moin.globalRemittance.dto.*;
import com.moin.globalRemittance.exception.RemittanceException;
import com.moin.globalRemittance.repository.custom.RemittanceRepository;
import com.moin.globalRemittance.service.model.QuoteDto;
import com.moin.globalRemittance.service.model.RemittanceDto;
import com.moin.globalRemittance.service.model.UserDto;
import com.moin.globalRemittance.utils.ForexUtil;
import com.moin.globalRemittance.utils.model.ForexDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@SpringBootTest
public class TransferServiceTest {

    private Logger log = LoggerFactory.getLogger(TransferServiceTest.class);

    @Autowired
    private TransferService transferService;

    @MockBean
    private RemittanceRepository remittanceRepository;

    @MockBean
    private ForexUtil forexUtil;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    private List<IForexDto> mockForexList() {
        ForexDto forexDto = new ForexDto();
        forexDto.setCurrencyCode("JPY");
        forexDto.setExchangeRate(BigDecimal.valueOf(8.7931));
        forexDto.setBasePrice(BigDecimal.valueOf(879.31));

        ForexDto usdForexDto = new ForexDto();
        usdForexDto.setCurrencyCode("USD");
        usdForexDto.setExchangeRate(BigDecimal.valueOf(1373.00));
        usdForexDto.setBasePrice(BigDecimal.valueOf(1373.00));

        return Arrays.asList(forexDto, usdForexDto);
    }

    private IUserDto mockUser(String idType) {
        UserDto userDto = new UserDto();
        userDto.setId(1);
        userDto.setUserId("test@email.com");
        userDto.setName("tester");
        userDto.setIdType(idType);
        return userDto;
    }

    private IRemittanceDto mockRemittance(BigDecimal usdAmount, boolean isExpired, Instant requestedTime) {
        return RemittanceDto.builder()
                .id(1)
                .userId(1)
                .sourceAmount(BigDecimal.valueOf(1000))
                .targetAmount(BigDecimal.valueOf(1100))
                .targetCurrency("USD")
                .exchangeRate(BigDecimal.valueOf(1.1))
                .fixedFee(BigDecimal.valueOf(10))
                .ratedFee(BigDecimal.valueOf(5))
                .feeRatio(BigDecimal.valueOf(0.01))
                .usdAmount(usdAmount)
                .usdExchangeRate(BigDecimal.valueOf(1))
                .expireTime(isExpired ?
                        Instant.now().minusSeconds(10)
                        : Instant.now().plusSeconds(10))
                .requestedTime(requestedTime)
                .build();
    }

    @Test
    public void testCreateQuote_USD_large_amount() {
        String currencyCode = "USD";
        IQuoteDto param = QuoteDto.builder()
                .amount(BigDecimal.valueOf(1000001L))
                .targetCurrency("USD")
                .build();

        when(forexUtil.getForexRateList(currencyCode)).thenReturn(mockForexList());
        when(remittanceRepository.createRemittance(any())).thenReturn(1);

        IQuoteBaseDto quote = transferService.createQuote(1, param);
        assertThat(quote).isNotNull();
        log.info(quote.toString());
    }

    @Test
    public void testCreateQuote_USD_small_amount() {
        String currencyCode = "USD";
        IQuoteDto param = QuoteDto.builder()
                .amount(BigDecimal.valueOf(100000L))
                .targetCurrency("USD")
                .build();

        when(forexUtil.getForexRateList(currencyCode)).thenReturn(mockForexList());
        when(remittanceRepository.createRemittance(any())).thenReturn(1);

        IQuoteBaseDto quote = transferService.createQuote(1, param);
        assertThat(quote).isNotNull();
        log.info(quote.toString());
    }

    @Test
    public void testCreateQuote_JPY() {
        String currencyCode = "JPY";
        IQuoteDto param = QuoteDto.builder()
                .amount(BigDecimal.valueOf(20000L))
                .targetCurrency(currencyCode)
                .build();

        when(forexUtil.getForexRateList(currencyCode)).thenReturn(mockForexList());
        when(remittanceRepository.createRemittance(any())).thenReturn(1);

        IQuoteBaseDto quote = transferService.createQuote(1, param);
        assertThat(quote).isNotNull();
        log.info(quote.toString());
    }

    @Test
    public void testCreateQuote_negative_amount() {
        String currencyCode = "USD";
        IQuoteDto param = QuoteDto.builder()
                .amount(BigDecimal.valueOf(-1L))
                .targetCurrency(currencyCode)
                .build();

        when(forexUtil.getForexRateList(currencyCode)).thenReturn(mockForexList());
        when(remittanceRepository.createRemittance(any())).thenReturn(1);

        RemittanceException thrown = assertThrows(RemittanceException.class, () -> {
            transferService.createQuote(1, param);
        });

        assertThat(thrown.getMessage()).isEqualTo("NEGATIVE_NUMBER");
        log.info(thrown.getMessage());
    }

    @Test
    public void testCreateQuote_negative_target_amount() {
        String currencyCode = "USD";
        IQuoteDto param = QuoteDto.builder()
                .amount(BigDecimal.valueOf(100L))
                .targetCurrency(currencyCode)
                .build();

        when(forexUtil.getForexRateList(currencyCode)).thenReturn(mockForexList());
        when(remittanceRepository.createRemittance(any())).thenReturn(1);

        RemittanceException thrown = assertThrows(RemittanceException.class, () -> {
            transferService.createQuote(1, param);
        });

        assertThat(thrown.getMessage()).isEqualTo("NEGATIVE_NUMBER");
        log.info(thrown.getMessage());
    }

    @Test
    public void testRequestRemittance_unknownError() {
        IUserDto user = mockUser("REG_NO");
        when(remittanceRepository.getWaitingRemittanceById(anyInt()))
                .thenReturn(null);

        RemittanceException thrown = assertThrows(RemittanceException.class, () -> {
            transferService.requestRemittance(user, 1);
        });

        assertThat(thrown.getMessage()).isEqualTo("UNKNOWN_ERROR");
        log.info(thrown.getMessage());
    }

    @Test
    public void testRequestRemittance_quoteExpired() {
        IUserDto user = mockUser("REG_NO");
        when(remittanceRepository.getWaitingRemittanceById(anyInt()))
                .thenReturn(mockRemittance(BigDecimal.valueOf(900), true, null));

        RemittanceException thrown = assertThrows(RemittanceException.class, () -> {
            transferService.requestRemittance(user, 1);;
        });

        assertThat(thrown.getMessage()).isEqualTo("QUOTE_EXPIRED");
        log.info(thrown.getMessage());
    }

    @Test
    public void testRequestRemittance_limitExcess_regNo() {
        IUserDto user = mockUser("REG_NO");
        when(remittanceRepository.getWaitingRemittanceById(anyInt()))
                .thenReturn(mockRemittance(BigDecimal.valueOf(100), false, null));
        when(remittanceRepository.getDailyRemittanceListByUser(anyInt()))
                .thenReturn(Collections.singletonList(mockRemittance(BigDecimal.valueOf(900), false, null)));


        RemittanceException thrown = assertThrows(RemittanceException.class, () -> {
            transferService.requestRemittance(user, 1);;
        });

        assertThat(thrown.getMessage()).isEqualTo("LIMIT_EXCESS");
        log.info(thrown.getMessage());
    }

    @Test
    public void testRequestRemittance_limitExcess_businessNo() {
        IUserDto user = mockUser("BUSINESS_NO");
        when(remittanceRepository.getWaitingRemittanceById(anyInt()))
                .thenReturn(mockRemittance(BigDecimal.valueOf(100), false, null));
        when(remittanceRepository.getDailyRemittanceListByUser(anyInt()))
                .thenReturn(Collections.singletonList(mockRemittance(BigDecimal.valueOf(4900), false, null)));


        RemittanceException thrown = assertThrows(RemittanceException.class, () -> {
            transferService.requestRemittance(user, 1);;
        });

        assertThat(thrown.getMessage()).isEqualTo("LIMIT_EXCESS");
        log.info(thrown.getMessage());
    }

    @Test
    public void testRequestRemittance_success_regNo() {
        IUserDto user = mockUser("REG_NO");
        when(remittanceRepository.getWaitingRemittanceById(anyInt()))
                .thenReturn(mockRemittance(BigDecimal.valueOf(99), false, null));
        when(remittanceRepository.getDailyRemittanceListByUser(anyInt()))
                .thenReturn(Collections.singletonList(mockRemittance(BigDecimal.valueOf(900), false, null)));
        transferService.requestRemittance(user, 1);
    }

    @Test
    public void testRequestRemittance_success_businessNo() {
        IUserDto user = mockUser("BUSINESS_NO");
        when(remittanceRepository.getWaitingRemittanceById(anyInt()))
                .thenReturn(mockRemittance(BigDecimal.valueOf(99), false, null));
        when(remittanceRepository.getDailyRemittanceListByUser(anyInt()))
                .thenReturn(Collections.singletonList(mockRemittance(BigDecimal.valueOf(4900), false, null)));
        transferService.requestRemittance(user, 1);
    }

    @Test
    public void testGetUserRemittanceHistory() {
        IUserDto user = mockUser("REG_NO");
        when(remittanceRepository.getDailyRemittanceListByUser(user.getId()))
                .thenReturn(Collections.singletonList(mockRemittance(BigDecimal.valueOf(4900), false, Instant.now())));

        IHistoryDto history = transferService.getUserRemittanceHistory(user);
        assertThat(history).isNotNull();
        log.info(history.toString());
    }
}
