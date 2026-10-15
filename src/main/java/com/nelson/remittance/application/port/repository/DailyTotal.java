package com.nelson.remittance.application.port.repository;

import java.math.BigDecimal;

// 접수 건수와 USD 합계를 한 번의 조회 결과로 묶어 서로 모순되지 않게 한다.
public record DailyTotal(int count, BigDecimal usdAmount) {}
