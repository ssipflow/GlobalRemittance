package com.moin.globalRemittance.repository;

import com.moin.globalRemittance.dto.IRemittanceDto;
import com.moin.globalRemittance.repository.custom.RemittanceRepository;
import com.moin.globalRemittance.repository.model.RemittanceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class RemittanceRepositoryTest {

    private final static Logger log = LoggerFactory.getLogger(RemittanceRepositoryTest.class);

    @Autowired
    private RemittanceRepository remittanceRepository;

    @BeforeEach
    public void setup() {
        RemittanceDto requestedRemittance = RemittanceDto.builder()
                .userId(1)
                .sourceAmount(BigDecimal.valueOf(1000001))
                .targetAmount(BigDecimal.valueOf(724.101053435115))
                .targetCurrency("USD")
                .exchangeRate(BigDecimal.valueOf(1375.50))
                .fixedFee(BigDecimal.valueOf(3000))
                .ratedFee(BigDecimal.valueOf(1000.001))
                .feeRatio(BigDecimal.valueOf(0.001))
                .usdAmount(BigDecimal.valueOf(724.101053435115))
                .usdExchangeRate(BigDecimal.valueOf(1375.50))
                .expireTime(Instant.now().minusMillis(5 * 60 * 1000))
                .requestedTime(Instant.now())
                .build();
        remittanceRepository.createRemittance(requestedRemittance);

        RemittanceDto waitingRemittance = RemittanceDto.builder()
                .userId(1)
                .sourceAmount(BigDecimal.valueOf(1000001))
                .targetAmount(BigDecimal.valueOf(724.101053435115))
                .targetCurrency("USD")
                .exchangeRate(BigDecimal.valueOf(1375.50))
                .fixedFee(BigDecimal.valueOf(3000))
                .ratedFee(BigDecimal.valueOf(1000.001))
                .feeRatio(BigDecimal.valueOf(0.001))
                .usdAmount(BigDecimal.valueOf(724.101053435115))
                .usdExchangeRate(BigDecimal.valueOf(1375.50))
                .expireTime(Instant.now().plusMillis(10 * 60 * 1000))
                .build();
        remittanceRepository.createRemittance(waitingRemittance);

        RemittanceDto forRequestRemittance = RemittanceDto.builder()
                .userId(1)
                .sourceAmount(BigDecimal.valueOf(1000001))
                .targetAmount(BigDecimal.valueOf(724.101053435115))
                .targetCurrency("USD")
                .exchangeRate(BigDecimal.valueOf(1375.50))
                .fixedFee(BigDecimal.valueOf(3000))
                .ratedFee(BigDecimal.valueOf(1000.001))
                .feeRatio(BigDecimal.valueOf(0.001))
                .usdAmount(BigDecimal.valueOf(724.101053435115))
                .usdExchangeRate(BigDecimal.valueOf(1375.50))
                .expireTime(Instant.now().plusMillis(10 * 60 * 1000))
                .build();
        remittanceRepository.createRemittance(forRequestRemittance);
    }

    @Test
    public void testCreateRemittance() {
        RemittanceDto remittance = RemittanceDto.builder()
                .userId(1)
                .sourceAmount(BigDecimal.valueOf(1000001))
                .targetAmount(BigDecimal.valueOf(724.101053435115))
                .targetCurrency("USD")
                .exchangeRate(BigDecimal.valueOf(1375.50))
                .fixedFee(BigDecimal.valueOf(3000))
                .ratedFee(BigDecimal.valueOf(1000.001))
                .feeRatio(BigDecimal.valueOf(0.001))
                .usdAmount(BigDecimal.valueOf(724.101053435115))
                .usdExchangeRate(BigDecimal.valueOf(1375.50))
                .expireTime(Instant.now().plusMillis(10 * 60 * 1000))
                .build();

        Integer id = remittanceRepository.createRemittance(remittance);
        assertThat(id).isGreaterThan(0);

        log.info("Generated RemittanceId: {}", id);
    }

    @Test
    public void testGetWaitingRemittanceById_success() {
        Integer id = 2;
        IRemittanceDto result = remittanceRepository.getWaitingRemittanceById(id);
        assertThat(result).isNotNull();
        assertThat(id).isEqualTo(result.getId());
        log.info(result.toString());
    }

    @Test
    public void testGetWaitingRemittanceById_Failed() {
        Integer id = 1;
        IRemittanceDto result = remittanceRepository.getWaitingRemittanceById(id);
        assertThat(result).isNull();
    }

    @Test
    public void testGetDailyRemittanceListByUser() {
        Integer id = 1;
        List<IRemittanceDto> result = remittanceRepository.getDailyRemittanceListByUser(id);
        assertThat(result).isNotNull();
        assertThat(result.size()).isGreaterThan(0);
        log.info(result.toString());
    }

    @Test
    public void testRequestRemittance() {
        Integer id = 3;
        Long updated = remittanceRepository.updateRequestedTime(id);
        assertThat(updated).isGreaterThan(0);

        IRemittanceDto remittanceDto = remittanceRepository.getRemittanceById(id);
        log.info(remittanceDto.getRequestedTime().toString());
    }
}

