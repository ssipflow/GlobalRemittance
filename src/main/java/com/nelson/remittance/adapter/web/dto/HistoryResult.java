package com.nelson.remittance.adapter.web.dto;

import java.math.BigDecimal;
import java.util.List;

// 오늘 전체 집계와 접수 이력 한 페이지를 반환하며 미접수 견적은 포함하지 않는다.
// nextCursor는 다음 페이지가 있을 때만 값이 있는 불투명 문자열이다.
public record HistoryResult(
        int resultCode,
        String resultMsg,
        String userId,
        String name,
        int todayTransferCount,
        BigDecimal todayTransferUsdAmount,
        List<HistoryView> history,
        String nextCursor) {}
