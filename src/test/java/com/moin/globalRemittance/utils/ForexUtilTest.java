package com.moin.globalRemittance.utils;

import com.moin.globalRemittance.dto.IForexDto;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SpringBootTest
public class ForexUtilTest {

    private Logger log = LoggerFactory.getLogger(ForexUtilTest.class);

    @Autowired
    private ForexUtil forexUtil;

    @Test
    public void testGetForexRateList_USD_success() {
        List<IForexDto> result = forexUtil.getForexRateList("USD");

        log.info(result.toString());
    }

    @Test
    public void testGetForexRateList_JPY_success() {
        List<IForexDto> result = forexUtil.getForexRateList("JPY");

        log.info(result.toString());
    }
}

