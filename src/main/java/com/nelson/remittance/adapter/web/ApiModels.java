package com.nelson.remittance.adapter.web;

import com.nelson.remittance.application.TransferUseCases;
import com.nelson.remittance.domain.Quote;
import com.nelson.remittance.domain.User;
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
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(TransferUseCases.KST);

    // 저장은 UTC를 유지하고 API 날짜 문자열을 생성하는 경계에서 KST로 표시한다.
    public static String time(Instant instant) {
        return KST_TIME.format(instant);
    }

    // 가입 입력의 검증과 민감 값의 로그 노출 방지를 함께 담당한다.
    public record Signup(
            @NotBlank @Email @Size(max = 255) String userId,
            @NotBlank @Size(max = 72) String password,
            @NotBlank @Size(max = 128) String name,
            @NotNull User.IdentityType idType,
            @NotBlank @Size(max = 64) String idValue) {
        @Override
        public String toString() {
            return "Signup[redacted]";
        }
    }

    // 로그인 입력을 검증하며 record 문자열 표현에 비밀번호를 노출하지 않는다.
    public record Login(
            @NotBlank @Email @Size(max = 255) String userId,
            @NotBlank @Size(max = 72) String password) {
        @Override
        public String toString() {
            return "Login[redacted]";
        }
    }

    // 입력 정밀도와 통화를 검증하며 금액의 업무 부호 판단은 도메인 정책에 맡긴다.
    public record QuoteInput(
            @NotNull @Digits(integer = 10, fraction = 12) BigDecimal amount,
            @NotBlank @Pattern(regexp = "USD|JPY") String targetCurrency) {}

    // 견적 ID를 양수 형식의 JSON 문자열로 받고 숫자 범위는 컨트롤러가 확인한다.
    public record RequestInput(@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String quoteId) {}

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

    // 오늘 집계와 전체 접수 이력을 함께 반환하며 미접수 견적은 포함하지 않는다.
    public record HistoryResult(
            int resultCode,
            String resultMsg,
            String userId,
            String name,
            int todayTransferCount,
            BigDecimal todayTransferUsdAmount,
            List<HistoryView> history) {}
}
