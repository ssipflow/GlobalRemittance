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
// 격리된 DB와 제어 가능한 시간으로 HTTP·영속성·동시성 계약을 검증한다.
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
        return login(email);
    }

    // 토큰은 주입된 시계 기준 30분 뒤 만료되므로 시계를 크게 옮긴 테스트는 다시 로그인한다.
    String login(String email) throws Exception {
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

    MvcResult request(String token, String id, String key) throws Exception {
        return mvc.perform(
                        post("/transfer/request")
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", key)
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

    MvcResult listPage(String token, String size, String cursor) throws Exception {
        var builder = get("/transfer/list").header("Authorization", "Bearer " + token);
        if (size != null) {
            builder = builder.param("size", size);
        }
        if (cursor != null) {
            builder = builder.param("cursor", cursor);
        }
        return mvc.perform(builder).andReturn();
    }

    JsonNode page(String token, String size, String cursor) throws Exception {
        MvcResult result = listPage(token, size, cursor);
        assertEquals(200, result.getResponse().getStatus());
        return read(result);
    }

    // 현재 시계 시각에 견적을 접수하고 접수 건의 ID를 돌려준다. 금액은 구분용 번호 n에서 만든 KRW 값이다.
    long requestedNow(String token, int n) throws Exception {
        String id = quote(token, krw(n), "USD");
        assertEquals(200, request(token, id).getResponse().getStatus());
        return Long.parseLong(id);
    }

    static String encodeForTest(Instant time, long id) {
        String raw = time.getEpochSecond() + "." + time.getNano() + "." + id;
        return java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String krw(int n) {
        return Integer.toString(100000 + n * 1000);
    }

    // 모든 페이지를 순서대로 읽어 amount 오름차순 식별용 requestedDate+sourceAmount 목록을 모은다.
    List<String> walk(String token, String size, List<JsonNode> pages) throws Exception {
        List<String> keys = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode body = page(token, size, cursor);
            pages.add(body);
            body.get("history")
                    .forEach(
                            h ->
                                    keys.add(
                                            h.get("requestedDate").asText()
                                                    + "|"
                                                    + h.get("sourceAmount").asText()));
            cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asText();
        } while (cursor != null);
        return keys;
    }

    void error(MvcResult result, int status, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentType().startsWith("application/json"));
        assertEquals(status, read(result).get("resultCode").asInt());
        assertEquals(code, read(result).get("resultMsg").asText());
    }

    @Test
    // 가입부터 접수까지 API 흐름과 암호화 저장·견적 감사 값 보존을 검증한다.
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
    // 동시 중복 가입에서 계정 유일성과 DB 진단 로그의 개인정보 차단을 검증한다.
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
    // 중복 가입과 잘못된 인증 정보가 안전한 오류 계약을 따르는지 확인한다.
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
    // UTF-8 바이트 경계로 BCrypt 절단과 암호문 열 길이 초과를 방지하는지 확인한다.
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
    // 다른 사용자 견적·없는 견적·중복 접수의 접근 경계를 검증한다.
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
    // 정확한 10분 만료 시 접수를 거부하고 미접수 견적은 집계하지 않는지 확인한다.
    void quoteExpiresAtExactTenMinutesAndNeverCountsAsRequested() throws Exception {
        String token = user("expiry@example.com", "REG_NO");
        String id = quote(token, "100000", "USD");
        clock.set(NOW.plusSeconds(600));
        error(request(token, id), 400, "QUOTE_EXPRIED");
        assertTrue(list(token).get("history").isEmpty());
    }

    @Test
    // 개인·사업자 한도는 경계까지 허용하고 1센트 초과는 거부하는지 확인한다.
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
    // KST 자정에 오늘 집계만 초기화하고 이전 이력은 보존하는지 확인한다.
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
    // 누락·잘못된·만료 토큰을 동일한 JSON 401 경계로 처리하는지 확인한다.
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
    // 잘못된 입력과 라우팅 오류도 공통 JSON 오류 형식을 유지하는지 확인한다.
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
    // 실제 서블릿 오류와 HTML 요청에서도 JSON 오류를 반환하는지 확인한다.
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
    // 실제 DB의 정수 ID·금액 정밀도·외래 키·인덱스 계약을 검증한다.
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
        assertEquals(
                "YES",
                jdbc.queryForObject(
                        "select is_nullable from information_schema.columns where table_name='REMITTANCES' and column_name='IDEMPOTENCY_KEY'",
                        String.class));
        // (user_id, idempotency_key) 유일 제약이 정확히 이 두 컬럼에 걸려 있어야 한다.
        assertEquals(
                List.of("USER_ID", "IDEMPOTENCY_KEY"),
                jdbc.queryForList(
                        "select cu.column_name from information_schema.table_constraints tc "
                                + "join information_schema.constraint_column_usage cu "
                                + "on cu.constraint_name=tc.constraint_name "
                                + "where tc.table_name='REMITTANCES' and tc.constraint_type='UNIQUE' "
                                + "and tc.constraint_name='UK_REMITTANCES_USER_IDEMPOTENCY_KEY' "
                                + "order by cu.column_name desc",
                        String.class));
    }

    List<String> race(long actor, String key, long first, long second) throws Exception {
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
                                        transfers.request(actor, id, key);
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
    // 동일 견적을 동시에 접수해도 한 번만 성공하는지 확인한다.
    void concurrentSameQuoteIsAcceptedExactlyOnce() throws Exception {
        String token = user("concurrent-same@example.com", "REG_NO");
        long id = Long.parseLong(quote(token, "100000", "USD"));
        long actor =
                jdbc.queryForObject(
                        "select id from users where user_id='concurrent-same@example.com'",
                        Long.class);
        List<String> outcomes = race(actor, null, id, id);
        assertEquals(1, Collections.frequency(outcomes, "OK"));
        assertEquals(1, Collections.frequency(outcomes, "ALREADY_REQUESTED"));
        assertEquals(1, list(token).get("todayTransferCount").asInt());
    }

    @Test
    // 서로 다른 견적의 동시 접수가 합산 일일 한도를 넘지 않는지 확인한다.
    void concurrentDifferentQuotesCannotJointlyExceedDailyLimit() throws Exception {
        String token = user("concurrent-limit@example.com", "REG_NO");
        long first = Long.parseLong(quote(token, "602602", "USD")),
                second = Long.parseLong(quote(token, "602602", "USD"));
        long actor =
                jdbc.queryForObject(
                        "select id from users where user_id='concurrent-limit@example.com'",
                        Long.class);
        List<String> outcomes = race(actor, null, first, second);
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

    // 별도 스레드의 트랜잭션이 사용자 행 잠금을 쥔 채 release 신호까지 대기하는 도우미다.
    record RowLock(CountDownLatch release, Future<?> holder) {
        void free() throws Exception {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    RowLock holdUserLock(long actor) throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        Future<?> holder =
                executor.submit(
                        () ->
                                new TransactionTemplate(manager)
                                        .execute(
                                                status -> {
                                                    jdbc.queryForList(
                                                            "select id from users where id=? for update",
                                                            actor);
                                                    locked.countDown();
                                                    try {
                                                        release.await(30, TimeUnit.SECONDS);
                                                    } catch (InterruptedException e) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                    return null;
                                                }));
        executor.shutdown();
        assertTrue(locked.await(10, TimeUnit.SECONDS));
        return new RowLock(release, holder);
    }

    @Test
    // 같은 키·같은 견적의 재시도는 200으로 재현되고 접수와 합계는 한 번만 반영된다.
    void sameKeyAndQuoteReplaysSuccessWithoutSecondEffect() throws Exception {
        String token = user("idem-replay@example.com", "REG_NO");
        String id = quote(token, "100000", "USD");
        assertEquals(200, request(token, id, "key-1").getResponse().getStatus());
        var first = list(token);
        clock.set(NOW.plusSeconds(60));
        MvcResult replay = request(token, id, "key-1");
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(200, read(replay).get("resultCode").asInt());
        var after = list(token);
        assertEquals(1, after.get("todayTransferCount").asInt());
        assertEquals(1, after.get("history").size());
        assertEquals(first.get("todayTransferUsdAmount"), after.get("todayTransferUsdAmount"));
        assertEquals(
                first.get("history").get(0).get("requestedDate"),
                after.get("history").get(0).get("requestedDate"));
        assertEquals(
                NOW,
                jdbc.queryForObject(
                        "select requested_time from remittances where id=?",
                        Instant.class,
                        Integer.valueOf(id)));
    }

    @Test
    // 견적 만료 후의 재현 요청은 만료 오류가 아니라 이미 접수된 건의 성공이다.
    void replayAfterQuoteExpiryIsStillSuccess() throws Exception {
        String token = user("idem-expired@example.com", "REG_NO");
        String id = quote(token, "100000", "USD");
        assertEquals(200, request(token, id, "key-exp").getResponse().getStatus());
        clock.set(NOW.plusSeconds(601));
        assertEquals(200, request(token, id, "key-exp").getResponse().getStatus());
        assertEquals(1, list(token).get("todayTransferCount").asInt());
    }

    @Test
    // 같은 키로 다른 견적을 보내면 422이며 두 번째 견적은 접수되지 않는다.
    void sameKeyWithDifferentQuoteIsRejectedAndNotRequested() throws Exception {
        String token = user("idem-reuse@example.com", "REG_NO");
        String first = quote(token, "100000", "USD");
        String second = quote(token, "200000", "USD");
        assertEquals(200, request(token, first, "key-r").getResponse().getStatus());
        error(request(token, second, "key-r"), 422, "IDEMPOTENCY_KEY_REUSED");
        assertNull(
                jdbc.queryForObject(
                        "select requested_time from remittances where id=?",
                        Instant.class,
                        Integer.valueOf(second)));
        assertEquals(1, list(token).get("todayTransferCount").asInt());
        // 키 없이 접수하는 길은 막지 않는다.
        assertEquals(200, request(token, second).getResponse().getStatus());
    }

    @Test
    // 키 범위는 사용자별이어서 다른 사용자의 같은 키 문자열은 서로 영향이 없다.
    void sameKeyStringIsIndependentAcrossUsers() throws Exception {
        String a = user("idem-a@example.com", "REG_NO");
        String b = user("idem-b@example.com", "REG_NO");
        String qa = quote(a, "100000", "USD");
        String qb = quote(b, "100000", "USD");
        assertEquals(200, request(a, qa, "shared").getResponse().getStatus());
        assertEquals(200, request(b, qb, "shared").getResponse().getStatus());
        assertEquals(1, list(a).get("todayTransferCount").asInt());
        assertEquals(1, list(b).get("todayTransferCount").asInt());
        // 타인 견적에 내 키를 쓰면 키 판단 없이 소유권 오류다.
        error(request(a, qb, "shared"), 403, "FORBIDDEN");
    }

    @Test
    // 실패한 시도는 키를 소비하지 않아 같은 키로 다른 견적이나 같은 견적을 다시 성공시킬 수 있다.
    void failedAttemptsDoNotConsumeKey() throws Exception {
        String token = user("idem-failed@example.com", "REG_NO");
        assertEquals(200, request(token, quote(token, "1004004", "USD")).getResponse().getStatus());
        String over = quote(token, "1012", "USD");
        error(request(token, over, "key-f"), 400, "LIMIT_EXCESS");
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from remittances where idempotency_key is not null",
                        Integer.class));
        // 다른 견적으로 같은 키를 쓸 수 있다.
        clock.set(Instant.parse("2026-01-01T15:00:00Z"));
        token = login("idem-failed@example.com");
        String other = quote(token, "100000", "USD");
        assertEquals(200, request(token, other, "key-f").getResponse().getStatus());
        // 만료로 실패한 견적도 키를 소비하지 않는다.
        String expiring = quote(token, "100000", "USD");
        clock.set(Instant.parse("2026-01-01T15:10:00Z"));
        error(request(token, expiring, "key-g"), 400, "QUOTE_EXPRIED");
        String fresh = quote(token, "100000", "USD");
        assertEquals(200, request(token, fresh, "key-g").getResponse().getStatus());
        assertEquals(3, list(token).get("history").size());
    }

    @Test
    // 재현 창은 now < 접수 시각 + 24시간이다. 직전은 200, 정확히 24시간은 기존 규칙의 409다.
    void replayWindowEndsExactlyTwentyFourHoursAfterRequest() throws Exception {
        String token = user("idem-window@example.com", "REG_NO");
        String id = quote(token, "100000", "USD");
        assertEquals(200, request(token, id, "key-w").getResponse().getStatus());
        clock.set(NOW.plusSeconds(24 * 3600 - 1));
        token = login("idem-window@example.com");
        assertEquals(200, request(token, id, "key-w").getResponse().getStatus());
        clock.set(NOW.plusSeconds(24 * 3600));
        error(request(token, id, "key-w"), 409, "ALREADY_REQUESTED");
        assertEquals(1, list(token).get("history").size());
    }

    @Test
    // 빈 값·65자·허용 외 문자 키는 400이고 접수도 일어나지 않는다. 키 없는 요청은 기존대로 동작한다.
    void invalidKeyFormatIsRejectedAndMissingKeyKeepsLegacyBehavior() throws Exception {
        String token = user("idem-format@example.com", "REG_NO");
        String id = quote(token, "100000", "USD");
        for (String bad : new String[] {"", "a".repeat(65), "bad key", "키", "a/b", "a,b"}) {
            error(request(token, id, bad), 400, "INVALID_REQUEST");
        }
        assertTrue(list(token).get("history").isEmpty());
        assertEquals(200, request(token, id, "a".repeat(64) + "").getResponse().getStatus());
        String legacy = quote(token, "100000", "USD");
        assertEquals(200, request(token, legacy).getResponse().getStatus());
        error(request(token, legacy), 409, "ALREADY_REQUESTED");
        // 키 없이 접수된 견적에 새 키를 붙여도 재현이 아니라 중복이다.
        error(request(token, legacy, "late-key"), 409, "ALREADY_REQUESTED");
        assertEquals(2, list(token).get("todayTransferCount").asInt());
    }

    @Test
    // 같은 키·같은 견적의 동시 요청은 모두 성공하고 한 번만 반영된다.
    void concurrentSameKeyAndQuoteSucceedsTwiceButEffectsOnce() throws Exception {
        String token = user("idem-race@example.com", "REG_NO");
        long id = Long.parseLong(quote(token, "100000", "USD"));
        long actor =
                jdbc.queryForObject(
                        "select id from users where user_id='idem-race@example.com'", Long.class);
        List<String> outcomes = race(actor, "race-key", id, id);
        assertEquals(List.of("OK", "OK"), outcomes);
        assertEquals(1, list(token).get("todayTransferCount").asInt());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from remittances where idempotency_key='race-key'",
                        Integer.class));
    }

    @Test
    // 같은 키로 서로 다른 견적을 동시에 보내면 하나만 접수되고 다른 하나는 키 재사용 오류다.
    void concurrentSameKeyDifferentQuotesAcceptsOnlyOne() throws Exception {
        String token = user("idem-race2@example.com", "REG_NO");
        long first = Long.parseLong(quote(token, "100000", "USD")),
                second = Long.parseLong(quote(token, "110000", "USD"));
        long actor =
                jdbc.queryForObject(
                        "select id from users where user_id='idem-race2@example.com'", Long.class);
        List<String> outcomes = race(actor, "race-key2", first, second);
        assertEquals(1, Collections.frequency(outcomes, "OK"));
        assertEquals(1, Collections.frequency(outcomes, "IDEMPOTENCY_KEY_REUSED"));
        assertEquals(1, list(token).get("todayTransferCount").asInt());
    }

    @Test
    // 락 대기가 한도를 넘으면 500이 아닌 503 LOCK_TIMEOUT을 빠르게 돌려주고 해제 후에는 접수된다.
    void lockWaitTimeoutIsBusinessErrorAndRecoversAfterRelease() throws Exception {
        String token = user("lock-timeout@example.com", "REG_NO");
        String id = quote(token, "100000", "USD");
        long actor =
                jdbc.queryForObject(
                        "select id from users where user_id='lock-timeout@example.com'",
                        Long.class);
        RowLock lock = holdUserLock(actor);
        try {
            long started = System.nanoTime();
            error(request(token, id), 503, "LOCK_TIMEOUT");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 5000, "락 대기가 설정 한도 근처에서 끝나야 한다: " + elapsedMs);
        } finally {
            lock.free();
        }
        assertEquals(200, request(token, id).getResponse().getStatus());
        assertEquals(1, list(token).get("todayTransferCount").asInt());
    }

    @Test
    // 이력 조회는 쓰기 락을 잡지 않으므로 다른 트랜잭션이 사용자 행 잠금을 쥐어도 막히지 않는다.
    void historyIsNotBlockedByUserRowLock() throws Exception {
        String token = user("history-free@example.com", "REG_NO");
        assertEquals(200, request(token, quote(token, "100000", "USD")).getResponse().getStatus());
        long actor =
                jdbc.queryForObject(
                        "select id from users where user_id='history-free@example.com'",
                        Long.class);
        RowLock lock = holdUserLock(actor);
        try {
            long started = System.nanoTime();
            var result = list(token);
            assertEquals(1, result.get("todayTransferCount").asInt());
            assertEquals(1, result.get("history").size());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
        } finally {
            lock.free();
        }
    }

    @Test
    // 일일 합계 구간은 [KST 00:00, 익일 00:00)이므로 자정 직전 접수는 전날, 자정 접수는 당일에 속한다.
    void dailyTotalWindowIsHalfOpenAtKstMidnight() throws Exception {
        clock.set(Instant.parse("2026-01-01T14:59:59Z"));
        String token = user("window@example.com", "REG_NO");
        assertEquals(200, request(token, quote(token, "1004004", "USD")).getResponse().getStatus());
        clock.set(Instant.parse("2026-01-01T15:00:00Z"));
        assertEquals(200, request(token, quote(token, "1004004", "USD")).getResponse().getStatus());
        error(request(token, quote(token, "1012", "USD")), 400, "LIMIT_EXCESS");
        assertEquals(1, list(token).get("todayTransferCount").asInt());
    }

    @Test
    // size=2로 5건을 읽으면 최신순으로 2/2/1건이 나오고 마지막 페이지만 nextCursor가 null이다.
    void pagesChainByCursorWithoutDuplicatesOrGaps() throws Exception {
        String token = user("pages@example.com", "REG_NO");
        for (int i = 1; i <= 5; i++) {
            clock.set(NOW.plusSeconds(i));
            requestedNow(token, i * 10);
        }
        var first = page(token, "2", null);
        assertEquals(2, first.get("history").size());
        assertEquals("150000", first.get("history").get(0).get("sourceAmount").asText());
        assertEquals("140000", first.get("history").get(1).get("sourceAmount").asText());
        assertTrue(first.get("nextCursor").isTextual());
        var second = page(token, "2", first.get("nextCursor").asText());
        assertEquals("130000", second.get("history").get(0).get("sourceAmount").asText());
        assertEquals("120000", second.get("history").get(1).get("sourceAmount").asText());
        var third = page(token, "2", second.get("nextCursor").asText());
        assertEquals(1, third.get("history").size());
        assertEquals("110000", third.get("history").get(0).get("sourceAmount").asText());
        assertTrue(third.get("nextCursor").isNull());
        List<JsonNode> pages = new ArrayList<>();
        assertEquals(5, walk(token, "2", pages).stream().distinct().count());
        // 정확히 size건인 마지막 페이지는 다음 페이지가 없으므로 nextCursor가 null이다.
        assertTrue(page(token, "5", null).get("nextCursor").isNull());
        // 기본 요청은 첫 페이지이며 기존 필드를 유지한다.
        var plain = list(token);
        assertEquals(5, plain.get("history").size());
        assertTrue(plain.get("nextCursor").isNull());
        assertEquals("pages@example.com", plain.get("userId").asText());
    }

    @Test
    // 같은 접수 시각의 거래는 ID 내림차순으로 정렬되고 페이지 경계가 같은 시각을 가로질러도 중복·누락이 없다.
    void sameRequestedTimeBreaksTiesByIdDescendingAcrossPages() throws Exception {
        String token = user("ties@example.com", "REG_NO");
        clock.set(NOW.plusSeconds(60));
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(requestedNow(token, i * 10));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            var body = page(token, "2", cursor);
            body.get("history").forEach(h -> seen.add(h.get("sourceAmount").asText()));
            cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asText();
        } while (cursor != null);
        assertEquals(List.of("150000", "140000", "130000", "120000", "110000"), seen);
        Collections.reverse(ids);
        assertEquals(
                ids,
                jdbc.queryForList(
                        "select id from remittances where requested_time is not null "
                                + "order by requested_time desc, id desc",
                        Long.class));
    }

    @Test
    // 페이지 사이에 새 접수가 생겨도 이미 받은 항목은 반복되지 않고 새 항목은 다음 페이지에 끼어들지 않는다.
    void newRequestBetweenPagesDoesNotRepeatReceivedItems() throws Exception {
        String token = user("between@example.com", "REG_NO");
        for (int i = 1; i <= 4; i++) {
            clock.set(NOW.plusSeconds(i));
            requestedNow(token, i * 10);
        }
        var first = page(token, "2", null);
        clock.set(NOW.plusSeconds(100));
        requestedNow(token, 99);
        var second = page(token, "2", first.get("nextCursor").asText());
        assertEquals(2, second.get("history").size());
        assertEquals("120000", second.get("history").get(0).get("sourceAmount").asText());
        assertEquals("110000", second.get("history").get(1).get("sourceAmount").asText());
        assertTrue(second.get("nextCursor").isNull());
        assertEquals(5, page(token, "10", null).get("history").size());
    }

    @Test
    // 오늘 집계는 페이지와 무관하게 같고, KST 자정 이전 항목은 이력에만 포함되고 집계에서는 제외된다.
    void todayTotalsAreSameOnEveryPageAndExcludeYesterday() throws Exception {
        clock.set(Instant.parse("2026-01-01T14:59:59Z"));
        String token = user("totals@example.com", "REG_NO");
        requestedNow(token, 1);
        clock.set(Instant.parse("2026-01-01T15:00:00Z"));
        requestedNow(token, 10);
        requestedNow(token, 20);
        requestedNow(token, 30);
        var first = page(token, "3", null);
        var second = page(token, "3", first.get("nextCursor").asText());
        // 오늘 합계는 첫 페이지의 당일 접수 3건 USD 합이며 어제 접수 1건은 포함하지 않는다.
        BigDecimal expected = BigDecimal.ZERO;
        for (var h : first.get("history")) {
            expected = expected.add(h.get("usdAmount").decimalValue());
        }
        for (var body : List.of(first, second)) {
            assertEquals(3, body.get("todayTransferCount").asInt());
            assertEquals(0, expected.compareTo(body.get("todayTransferUsdAmount").decimalValue()));
        }
        assertEquals(1, second.get("history").size());
        assertEquals(
                "2026-01-01 23:59:59", second.get("history").get(0).get("requestedDate").asText());
        assertTrue(second.get("nextCursor").isNull());
    }

    @Test
    // 잘못된 cursor와 허용 범위 밖 size는 공통 JSON 400이다.
    void invalidCursorAndSizeAreJson400() throws Exception {
        String token = user("invalid-page@example.com", "REG_NO");
        requestedNow(token, 10);
        requestedNow(token, 20);
        String valid = page(token, "1", null).get("nextCursor").asText();
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        for (String bad :
                List.of(
                        "!!!",
                        "",
                        valid + "!",
                        encoder.encodeToString(
                                "a.b.c".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        encoder.encodeToString(
                                "1.2".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        encoder.encodeToString(
                                "1.0.0".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        encoder.encodeToString(
                                "1.0.-5".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        encoder.encodeToString(
                                "1.1000000000.1".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        encoder.encodeToString(
                                "1.0.99999999999"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        encoder.encodeToString(
                                "99999999999999999999.0.1"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        encoder.encodeToString(
                                "01.0.1".getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
            error(listPage(token, null, bad), 400, "INVALID_REQUEST");
        }
        for (String size : List.of("0", "101", "-1", "+5", "abc", "", "1.5", "99999999999")) {
            error(listPage(token, size, null), 400, "INVALID_REQUEST");
        }
        assertEquals(1, page(token, "1", valid).get("history").size());
        assertEquals(2, page(token, "100", null).get("history").size());
    }

    @Test
    // 다른 사용자의 cursor를 써도 조회는 인증 사용자로 제한되어 타인의 거래가 보이지 않는다.
    void foreignCursorNeverExposesOtherUsersHistory() throws Exception {
        String owner = user("cursor-owner@example.com", "REG_NO");
        String other = user("cursor-other@example.com", "REG_NO");
        clock.set(NOW.plusSeconds(10));
        requestedNow(owner, 11);
        requestedNow(owner, 12);
        requestedNow(owner, 13);
        clock.set(NOW.plusSeconds(1));
        requestedNow(other, 77);
        String ownerCursor = page(owner, "1", null).get("nextCursor").asText();
        // 소유자의 위치값은 정렬 기준일 뿐이다. 결과는 인증 사용자(other)의 거래로만 구성된다.
        var body = page(other, "10", ownerCursor);
        assertEquals("cursor-other@example.com", body.get("userId").asText());
        assertEquals(1, body.get("history").size());
        assertEquals(krw(77), body.get("history").get(0).get("sourceAmount").asText());
        var newer = page(other, "10", encodeForTest(NOW.minusSeconds(100), 1));
        assertEquals(0, newer.get("history").size());
    }

    @Test
    // 접수 시각 초 값은 0..9999-12-31T23:59:59Z(253402300799)만 허용하며 범위 밖 커서는 500이 아닌 400이다.
    void cursorSecondsOutsideSupportedRangeAreJson400() throws Exception {
        String token = user("range@example.com", "REG_NO");
        requestedNow(token, 10);
        for (String raw :
                List.of(
                        "31556889864403199.0.1",
                        "253402300800.0.1",
                        "-1.0.1",
                        "-31557014167219200.0.1")) {
            var cursor =
                    java.util.Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            error(listPage(token, null, cursor), 400, "INVALID_REQUEST");
        }
        for (String raw : List.of("0.0.1", "253402300799.999999999.1")) {
            var cursor =
                    java.util.Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(200, listPage(token, null, cursor).getResponse().getStatus());
        }
    }
}
