package com.nelson.remittance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nelson.remittance.application.TransferUseCases;
import com.nelson.remittance.domain.BusinessException;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RemittanceApiTest.TestBeans.class)
@org.junit.jupiter.api.extension.ExtendWith(
        org.springframework.boot.test.system.OutputCaptureExtension.class)
class RemittanceApiTest {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired TransferUseCases transfers;
    @Autowired MutableClock clock;

    @BeforeEach
    void reset() {
        clock.set(NOW);
        new TransactionTemplate(manager)
                .execute(
                        status -> {
                            em.createQuery("delete from QuoteEntity").executeUpdate();
                            em.createQuery("delete from UserEntity").executeUpdate();
                            em.clear();
                            return null;
                        });
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        FailureEndpoint failureEndpoint() {
            return new FailureEndpoint();
        }
    }

    @RestController
    static class FailureEndpoint {
        @GetMapping("/_test/failure")
        Object fail() {
            throw new IllegalStateException("private diagnostic detail");
        }

        @GetMapping("/_test/send-error")
        void servletError(jakarta.servlet.http.HttpServletResponse response)
                throws java.io.IOException {
            response.sendError(500, "private diagnostic detail");
        }
    }

    JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    String payload(Object data) throws Exception {
        return json.writeValueAsString(data);
    }

    MvcResult signup(String email, String type, String password, String identity) throws Exception {
        return mvc.perform(
                        post("/user/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        payload(
                                                Map.of(
                                                        "userId",
                                                        email,
                                                        "password",
                                                        password,
                                                        "name",
                                                        "홍길동",
                                                        "idType",
                                                        type,
                                                        "idValue",
                                                        identity))))
                .andReturn();
    }

    String user(String email, String type) throws Exception {
        assertEquals(
                200,
                signup(email, type, "test-password", "1234567890123").getResponse().getStatus());
        var login =
                mvc.perform(
                                post("/user/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                payload(
                                                        Map.of(
                                                                "userId",
                                                                email,
                                                                "password",
                                                                "test-password"))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.resultCode").value(200))
                        .andReturn();
        return read(login).get("token").asText();
    }

    String quote(String token, String amount, String currency) throws Exception {
        var result =
                mvc.perform(
                                post("/transfer/quote")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                payload(
                                                        Map.of(
                                                                "amount",
                                                                new BigDecimal(amount),
                                                                "targetCurrency",
                                                                currency))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.quote.quoteId").isString())
                        .andExpect(jsonPath("$.quote.exchangeRate").isNumber())
                        .andReturn();
        return read(result).get("quote").get("quoteId").asText();
    }

    MvcResult request(String token, String id) throws Exception {
        return mvc.perform(
                        post("/transfer/request")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload(Map.of("quoteId", id))))
                .andReturn();
    }

    JsonNode list(String token) throws Exception {
        return read(
                mvc.perform(get("/transfer/list").header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    void error(MvcResult result, int status, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentType().startsWith("application/json"));
        assertEquals(status, read(result).get("resultCode").asInt());
        assertEquals(code, read(result).get("resultMsg").asText());
    }

    @Test
    void completeFlowPersistsProtectedIdentityAndImmutableFinancialAudit() throws Exception {
        String token = user("flow@example.com", "REG_NO");
        var stored =
                jdbc.queryForMap(
                        "select password,id_value from users where user_id='flow@example.com'");
        assertTrue(stored.get("PASSWORD").toString().startsWith("$2"));
        assertNotEquals("test-password", stored.get("PASSWORD"));
        assertNotEquals("1234567890123", stored.get("ID_VALUE"));
        assertTrue(stored.get("ID_VALUE").toString().length() <= 128);
        String id = quote(token, "100000", "USD");
        assertTrue(list(token).get("history").isEmpty());
        var audit = jdbc.queryForMap("select * from remittances where id=?", Integer.valueOf(id));
        assertNull(audit.get("REQUESTED_TIME"));
        assertEquals(
                0,
                new BigDecimal("1200")
                        .compareTo(
                                ((BigDecimal) audit.get("FIXED_FEE"))
                                        .add((BigDecimal) audit.get("RATED_FEE"))));
        assertEquals(200, request(token, id).getResponse().getStatus());
        var history = list(token);
        assertEquals(1, history.get("todayTransferCount").asInt());
        assertEquals(
                0,
                new BigDecimal("98.80")
                        .compareTo(history.get("todayTransferUsdAmount").decimalValue()));
        assertEquals(
                "2026-01-01 09:00:00", history.get("history").get(0).get("requestedDate").asText());
        var after = jdbc.queryForMap("select * from remittances where id=?", Integer.valueOf(id));
        for (String field :
                List.of(
                        "EXCHANGE_RATE",
                        "FIXED_FEE",
                        "RATED_FEE",
                        "USD_EXCHANGE_RATE",
                        "USD_AMOUNT",
                        "TARGET_AMOUNT")) {
            assertEquals(audit.get(field), after.get(field));
        }
    }

    @Test
    void concurrentSignupPreservesUniqueUserAndDoesNotLogEmail(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String email = "privacy-race-marker@example.invalid";
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> jobs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                jobs.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("start timed out");
                                    }
                                    return signup(
                                            email, "REG_NO", "test-password", "1234567890123");
                                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var first = jobs.get(0).get(15, TimeUnit.SECONDS);
            var second = jobs.get(1).get(15, TimeUnit.SECONDS);
            assertEquals(
                    List.of(200, 409),
                    java.util.stream.Stream.of(
                                    first.getResponse().getStatus(),
                                    second.getResponse().getStatus())
                            .sorted()
                            .toList());
            error(first.getResponse().getStatus() == 409 ? first : second, 409, "DUPLICATE_USER");
            assertEquals(1, jdbc.queryForObject("select count(*) from users", Integer.class));
            assertFalse(
                    output.getAll().contains(email),
                    "DB constraint diagnostics exposed the synthetic userId");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void duplicateSignupAndInvalidCredentialsReturnSafeErrors() throws Exception {
        user("duplicate@example.com", "REG_NO");
        error(
                signup("duplicate@example.com", "REG_NO", "other-password", "3333333333333"),
                409,
                "DUPLICATE_USER");
        error(
                mvc.perform(
                                post("/user/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                payload(
                                                        Map.of(
                                                                "userId",
                                                                "duplicate@example.com",
                                                                "password",
                                                                "bad-password"))))
                        .andReturn(),
                401,
                "UNAUTHORIZED");
        assertEquals(1, jdbc.queryForObject("select count(*) from users", Integer.class));
    }

    @Test
    void utf8ByteLimitsPreventBcryptTruncationAndCiphertextOverflow() throws Exception {
        error(
                signup("long-pass@example.com", "REG_NO", "한".repeat(25), "1234567890123"),
                400,
                "INVALID_REQUEST");
        error(
                signup("long-id@example.com", "REG_NO", "test-password", "한".repeat(22)),
                400,
                "INVALID_REQUEST");
        assertEquals(
                200,
                signup("boundary@example.com", "REG_NO", "한".repeat(24), "한".repeat(21))
                        .getResponse()
                        .getStatus());
        assertTrue(jdbc.queryForObject("select length(id_value) from users", Integer.class) <= 128);
    }

    @Test
    void foreignOwnerAndUnknownQuoteCannotRequestThenDuplicateIsRejected() throws Exception {
        String owner = user("owner@example.com", "REG_NO"),
                other = user("other@example.com", "REG_NO");
        String id = quote(owner, "100000", "USD");
        // 별도 트랜잭션의 LAZY 사용자 프록시를 매핑하는 경로도 함께 검증한다.
        error(request(other, id), 403, "FORBIDDEN");
        error(request(owner, "2147483647"), 404, "QUOTE_NOT_FOUND");
        assertNull(
                jdbc.queryForObject(
                        "select requested_time from remittances where id=?",
                        Instant.class,
                        Integer.valueOf(id)));
        assertEquals(200, request(owner, id).getResponse().getStatus());
        error(request(owner, id), 409, "ALREADY_REQUESTED");
        assertEquals(1, list(owner).get("todayTransferCount").asInt());
        assertTrue(list(other).get("history").isEmpty());
    }

    @Test
    void quoteExpiresAtExactTenMinutesAndNeverCountsAsRequested() throws Exception {
        String token = user("expiry@example.com", "REG_NO");
        String id = quote(token, "100000", "USD");
        clock.set(NOW.plusSeconds(600));
        error(request(token, id), 400, "QUOTE_EXPRIED");
        assertTrue(list(token).get("history").isEmpty());
    }

    @Test
    void personalAndBusinessLimitsAreInclusiveAndCentBeyondIsRejected() throws Exception {
        String personal = user("personal@example.com", "REG_NO");
        assertEquals(
                200,
                request(personal, quote(personal, "1004004", "USD")).getResponse().getStatus());
        assertEquals(
                0,
                new BigDecimal("1000.00")
                        .compareTo(list(personal).get("todayTransferUsdAmount").decimalValue()));
        error(request(personal, quote(personal, "1012", "USD")), 400, "LIMIT_EXCESS");
        String business = user("business@example.com", "BUSINESS_NO");
        assertEquals(
                200,
                request(business, quote(business, "5008008", "USD")).getResponse().getStatus());
        assertEquals(
                0,
                new BigDecimal("5000.00")
                        .compareTo(list(business).get("todayTransferUsdAmount").decimalValue()));
        error(request(business, quote(business, "1012", "USD")), 400, "LIMIT_EXCESS");
    }

    @Test
    void kstDayResetsDailyTotalsWhileHistoryIncludesPriorRequests() throws Exception {
        clock.set(Instant.parse("2026-01-01T14:59:00Z"));
        String token = user("kst@example.com", "REG_NO");
        String first = quote(token, "1004004", "USD");
        assertEquals(200, request(token, first).getResponse().getStatus());
        clock.set(Instant.parse("2026-01-01T15:00:00Z"));
        assertEquals(0, list(token).get("todayTransferCount").asInt());
        assertEquals(1, list(token).get("history").size());
        String second = quote(token, "1004004", "USD");
        assertEquals(200, request(token, second).getResponse().getStatus());
        var result = list(token);
        assertEquals(1, result.get("todayTransferCount").asInt());
        assertEquals(2, result.get("history").size());
        assertEquals(
                "2026-01-02 00:00:00", result.get("history").get(0).get("requestedDate").asText());
        assertEquals(
                "2026-01-01 23:59:00", result.get("history").get(1).get("requestedDate").asText());
    }

    @Test
    void missingInvalidAndExpiredTokenAreJson401() throws Exception {
        error(mvc.perform(get("/transfer/list")).andReturn(), 401, "UNAUTHORIZED");
        error(
                mvc.perform(get("/transfer/list").header("Authorization", "Bearer garbage"))
                        .andReturn(),
                401,
                "UNAUTHORIZED");
        String token = user("expired-token@example.com", "REG_NO");
        clock.set(NOW.plusSeconds(1800));
        error(
                mvc.perform(get("/transfer/list").header("Authorization", "Bearer " + token))
                        .andReturn(),
                401,
                "UNAUTHORIZED");
    }

    @Test
    void malformedInputAndRoutingErrorsAllUseJsonContract() throws Exception {
        String token = user("errors@example.com", "REG_NO");
        error(
                mvc.perform(
                                post("/transfer/quote")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{broken"))
                        .andReturn(),
                400,
                "INVALID_REQUEST");
        error(
                mvc.perform(post("/user/login").contentType(MediaType.TEXT_PLAIN).content("{}"))
                        .andReturn(),
                415,
                "UNSUPPORTED_MEDIA_TYPE");
        error(mvc.perform(get("/missing")).andReturn(), 404, "NOT_FOUND");
        error(mvc.perform(get("/user/signup")).andReturn(), 405, "METHOD_NOT_ALLOWED");
        error(mvc.perform(get("/_test/failure")).andReturn(), 500, "UNKNOWN_ERROR");
        for (String content :
                List.of(
                        "{\"amount\":100000,\"targetCurrency\":\"EUR\"}",
                        "{\"amount\":1E1000000000,\"targetCurrency\":\"USD\"}",
                        "{\"amount\":\"10000\",\"targetCurrency\":\"USD\"}")) {
            error(
                    mvc.perform(
                                    post("/transfer/quote")
                                            .header("Authorization", "Bearer " + token)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(content))
                            .andReturn(),
                    400,
                    "INVALID_REQUEST");
        }
        error(
                mvc.perform(
                                post("/transfer/quote")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"amount\":-1,\"targetCurrency\":\"USD\"}"))
                        .andReturn(),
                400,
                "NEGATIVE_NUMBER");
        error(
                mvc.perform(
                                post("/transfer/request")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"quoteId\":1}"))
                        .andReturn(),
                400,
                "INVALID_REQUEST");
        error(request(token, "9223372036854775808"), 400, "INVALID_REQUEST");
    }

    @Test
    void actualServletErrorDispatchAndHtmlAcceptStillReturnJson() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        for (String path : List.of("/error", "/missing", "/_test/send-error")) {
            var request =
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("http://localhost:" + port + path))
                            .header("Accept", "text/html")
                            .GET()
                            .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            int expected = path.equals("/_test/send-error") ? 500 : 404;
            assertEquals(expected, response.statusCode());
            assertTrue(
                    response.headers()
                            .firstValue("content-type")
                            .orElse("")
                            .startsWith("application/json"));
            assertEquals(expected, json.readTree(response.body()).get("resultCode").asInt());
            assertFalse(response.body().contains("private diagnostic detail"));
            assertFalse(response.body().contains("<html"));
        }
    }

    @Test
    void schemaHasIntegerIdsDecimalAuditForeignKeyAndIndex() {
        assertEquals(
                "INTEGER",
                jdbc.queryForObject(
                        "select data_type from information_schema.columns where table_name='USERS' and column_name='ID'",
                        String.class));
        assertEquals(
                "INTEGER",
                jdbc.queryForObject(
                        "select data_type from information_schema.columns where table_name='REMITTANCES' and column_name='USER_ID'",
                        String.class));
        assertEquals(
                22,
                jdbc.queryForObject(
                        "select numeric_precision from information_schema.columns where table_name='REMITTANCES' and column_name='SOURCE_AMOUNT'",
                        Integer.class));
        assertEquals(
                12,
                jdbc.queryForObject(
                        "select numeric_scale from information_schema.columns where table_name='REMITTANCES' and column_name='SOURCE_AMOUNT'",
                        Integer.class));
        assertEquals(
                "YES",
                jdbc.queryForObject(
                        "select is_nullable from information_schema.columns where table_name='REMITTANCES' and column_name='REQUESTED_TIME'",
                        String.class));
        assertTrue(
                jdbc.queryForObject(
                                "select count(*) from information_schema.table_constraints where table_name='REMITTANCES' and constraint_type='FOREIGN KEY'",
                                Integer.class)
                        > 0);
        assertTrue(
                jdbc.queryForObject(
                                "select count(*) from information_schema.indexes where index_name='IDX_REMITTANCES_USER_REQUESTED'",
                                Integer.class)
                        > 0);
    }

    List<String> race(long actor, long first, long second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<String>> jobs = new ArrayList<>();
            for (long id : new long[] {first, second}) {
                jobs.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("start timed out");
                                    }
                                    try {
                                        transfers.request(actor, id);
                                        return "OK";
                                    } catch (BusinessException failure) {
                                        return failure.code().name();
                                    }
                                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(
                    jobs.get(0).get(15, TimeUnit.SECONDS), jobs.get(1).get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameQuoteIsAcceptedExactlyOnce() throws Exception {
        String token = user("concurrent-same@example.com", "REG_NO");
        long id = Long.parseLong(quote(token, "100000", "USD"));
        long actor =
                jdbc.queryForObject(
                        "select id from users where user_id='concurrent-same@example.com'",
                        Long.class);
        List<String> outcomes = race(actor, id, id);
        assertEquals(1, Collections.frequency(outcomes, "OK"));
        assertEquals(1, Collections.frequency(outcomes, "ALREADY_REQUESTED"));
        assertEquals(1, list(token).get("todayTransferCount").asInt());
    }

    @Test
    void concurrentDifferentQuotesCannotJointlyExceedDailyLimit() throws Exception {
        String token = user("concurrent-limit@example.com", "REG_NO");
        long first = Long.parseLong(quote(token, "602602", "USD")),
                second = Long.parseLong(quote(token, "602602", "USD"));
        long actor =
                jdbc.queryForObject(
                        "select id from users where user_id='concurrent-limit@example.com'",
                        Long.class);
        List<String> outcomes = race(actor, first, second);
        assertEquals(1, Collections.frequency(outcomes, "OK"));
        assertEquals(1, Collections.frequency(outcomes, "LIMIT_EXCESS"));
        assertEquals(1, list(token).get("todayTransferCount").asInt());
        assertTrue(
                list(token)
                                .get("todayTransferUsdAmount")
                                .decimalValue()
                                .compareTo(new BigDecimal("1000.00"))
                        <= 0);
    }
}
