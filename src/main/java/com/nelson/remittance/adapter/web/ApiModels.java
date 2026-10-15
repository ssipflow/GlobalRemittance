package com.nelson.remittance.adapter.web;

import com.nelson.remittance.domain.BusinessDay;
import com.nelson.remittance.domain.Quote;
import com.nelson.remittance.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

// 입력 검증과 출력 형태를 정의하는 웹 모델이다. 업무 계산과 영속 모델은 포함하지 않는다.
public final class ApiModels {
    private ApiModels() {}

    private static final DateTimeFormatter KST_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BusinessDay.KST);

    // 저장은 UTC를 유지하고 API 날짜 문자열을 생성하는 경계에서 KST로 표시한다.
    public static String time(Instant instant) {
        return KST_TIME.format(instant);
    }

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

    // 견적 ID를 양수 형식의 JSON 문자열로 받고 숫자 범위는 컨트롤러가 확인한다.
    public record RequestInput(
            @Schema(description = "견적 응답의 quoteId. 양의 숫자 문자열이며 INT 범위 이내", example = "1")
                    @NotBlank
                    @Pattern(regexp = "[1-9][0-9]{0,18}")
                    String quoteId) {}

    // 성공·실패의 공통 상태 코드와 메시지 형식이다.
    public record Result(int resultCode, String resultMsg) {
        public static Result ok() {
            return new Result(200, "OK");
        }
    }

    // 로그인 성공 시 인증 토큰을 함께 반환한다.
    public record LoginResult(int resultCode, String resultMsg, String token) {}

    // 영속 모델 없이 공개 견적 필드와 KST 만료 시각을 전달한다.
    public record QuoteView(
            String quoteId, BigDecimal exchangeRate, String expireTime, BigDecimal targetAmount) {
        public static QuoteView of(Quote quote) {
            var c = quote.calculation();
            return new QuoteView(
                    Long.toString(quote.id()),
                    c.exchangeRate(),
                    time(quote.expireTime()),
                    c.targetAmount());
        }
    }

    // 공통 성공 응답 안에 견적을 중첩한다.
    public record QuoteResult(int resultCode, String resultMsg, QuoteView quote) {}

    // 접수 거래의 금액·환율·수수료 감사 값과 KST 접수 날짜를 표시한다.
    public record HistoryView(
            BigDecimal sourceAmount,
            BigDecimal fee,
            BigDecimal usdExchangeRate,
            BigDecimal usdAmount,
            String targetCurrency,
            BigDecimal exchangeRate,
            BigDecimal targetAmount,
            String requestedDate) {
        public static HistoryView of(Quote quote) {
            var c = quote.calculation();
            return new HistoryView(
                    c.sourceAmount(),
                    c.fee(),
                    c.usdExchangeRate(),
                    c.usdAmount(),
                    c.targetCurrency(),
                    c.exchangeRate(),
                    c.targetAmount(),
                    time(quote.requestedTime()));
        }
    }

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
}
