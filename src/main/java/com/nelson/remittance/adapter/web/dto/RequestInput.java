package com.nelson.remittance.adapter.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// 견적 ID를 양수 형식의 JSON 문자열로 받고 숫자 범위는 컨트롤러가 확인한다.
public record RequestInput(
        @Schema(description = "견적 응답의 quoteId. 양의 숫자 문자열이며 INT 범위 이내", example = "1")
                @NotBlank
                @Pattern(regexp = "[1-9][0-9]{0,18}")
                String quoteId) {}
