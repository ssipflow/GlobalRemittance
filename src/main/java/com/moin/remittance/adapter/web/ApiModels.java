package com.moin.remittance.adapter.web;

import com.moin.remittance.application.TransferUseCases;
import com.moin.remittance.domain.Quote;
import com.moin.remittance.domain.User;
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

public final class ApiModels {
    private ApiModels() {}

    private static final DateTimeFormatter KST_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(TransferUseCases.KST);

    public static String time(Instant instant) {
        return KST_TIME.format(instant);
    }

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

    public record Login(
            @NotBlank @Email @Size(max = 255) String userId,
            @NotBlank @Size(max = 72) String password) {
        @Override
        public String toString() {
            return "Login[redacted]";
        }
    }

    public record QuoteInput(
            @NotNull @Digits(integer = 10, fraction = 12) BigDecimal amount,
            @NotBlank @Pattern(regexp = "USD|JPY") String targetCurrency) {}

    public record RequestInput(@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String quoteId) {}

    public record Result(int resultCode, String resultMsg) {
        public static Result ok() {
            return new Result(200, "OK");
        }
    }

    public record LoginResult(int resultCode, String resultMsg, String token) {}

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

    public record QuoteResult(int resultCode, String resultMsg, QuoteView quote) {}

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

    public record HistoryResult(
            int resultCode,
            String resultMsg,
            String userId,
            String name,
            int todayTransferCount,
            BigDecimal todayTransferUsdAmount,
            List<HistoryView> history) {}
}
