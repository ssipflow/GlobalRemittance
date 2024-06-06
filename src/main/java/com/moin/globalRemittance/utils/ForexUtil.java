package com.moin.globalRemittance.utils;

import com.moin.globalRemittance.dto.IForexDto;
import com.moin.globalRemittance.utils.model.ForexDto;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ForexUtil {

    private static final Logger logger = LoggerFactory.getLogger(ForexUtil.class);

    private final RestTemplate restTemplate;

    @Value("${variables.api.forex.url}")
    private String forexApiUrl;

    public List<IForexDto> getForexRateList(String currencyCode) {
        Set<String> currencySet = new HashSet<>(Set.of("USD"));
        currencySet.add(currencyCode);

        String param = currencySet.stream()
                .map(target -> "FRX.KRW" + target)
                .collect(Collectors.joining(","));;

        String url = String.format("%s?codes=,%s", forexApiUrl, param);

        try {
            ForexDto[] forexArr = restTemplate.getForObject(url, ForexDto[].class);
            if (forexArr == null || forexArr.length == 0) {
                throw new RuntimeException("There is no forex data");
            }
            return Arrays.stream(forexArr).peek(ForexDto::calculateExchangeRate).collect(Collectors.toList());
        } catch(HttpClientErrorException e) {
            logger.error(e.getMessage());
            throw new RuntimeException("Error fetching forex rates: " + e.getMessage());
        }
    }
}
