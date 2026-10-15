package com.nelson.remittance.adapter.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

// 입력 정밀도와 통화를 검증하며 금액의 업무 부호 판단은 도메인 정책에 맡긴다.
public record QuoteInput(
        @Schema(description = "KRW 금액. 양수이며 정수부 최대 10자리, 소수부 최대 12자리", example = "400000")
                @NotNull
                @Digits(integer = 10, fraction = 12)
                BigDecimal amount,
        @Schema(
                        description = "수취 통화",
                        allowableValues = {"USD", "JPY"},
                        example = "USD")
                @NotBlank
                @Pattern(regexp = "USD|JPY")
                String targetCurrency) {}
