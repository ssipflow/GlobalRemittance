package com.nelson.remittance.adapter.web.dto;

import com.nelson.remittance.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 가입 입력의 검증과 민감 값의 로그 노출 방지를 함께 담당한다.
public record Signup(
        @Schema(description = "이메일 형식의 사용자 ID(유일)", example = "demo@example.com")
                @NotBlank
                @Email
                @Size(max = 255)
                String userId,
        @Schema(description = "비밀번호. 최대 72자이며 UTF-8 72바이트 이하", example = "example-password")
                @NotBlank
                @Size(max = 72)
                String password,
        @Schema(description = "이름", example = "테스트 사용자") @NotBlank @Size(max = 128) String name,
        @Schema(description = "식별번호 종류. REG_NO는 개인, BUSINESS_NO는 법인") @NotNull
                User.IdentityType idType,
        @Schema(description = "식별번호. 최대 64자이며 UTF-8 64바이트 이하", example = "example-identity")
                @NotBlank
                @Size(max = 64)
                String idValue) {
    @Override
    public String toString() {
        return "Signup[redacted]";
    }
}
