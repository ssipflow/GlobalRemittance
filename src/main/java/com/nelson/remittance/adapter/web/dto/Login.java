package com.nelson.remittance.adapter.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 로그인 입력을 검증하며 record 문자열 표현에 비밀번호를 노출하지 않는다.
public record Login(
        @Schema(description = "가입한 이메일", example = "demo@example.com")
                @NotBlank
                @Email
                @Size(max = 255)
                String userId,
        @Schema(description = "비밀번호. 최대 72자이며 UTF-8 72바이트 이하", example = "example-password")
                @NotBlank
                @Size(max = 72)
                String password) {
    @Override
    public String toString() {
        return "Login[redacted]";
    }
}
