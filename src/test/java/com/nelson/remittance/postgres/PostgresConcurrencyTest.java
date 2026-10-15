package com.nelson.remittance.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

// 사용자 행 잠금으로 접수를 직렬화하는 동시성 보장과 락 타임아웃 매핑을 실제 PostgreSQL에서 검증한다.
// H2 시나리오(RemittanceApiTest)와 달리 서비스가 아닌 HTTP 경계로 요청해 SQLState 55P03 변환까지 본다.
// Docker가 필요하며 없으면 건너뛰지 않고 실패한다. postgresTest로만 실행된다.
@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "postgres"})
class PostgresConcurrencyTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        // 클래스당 컨테이너 하나를 컨텍스트 생성 전에 띄우며 종료는 Testcontainers(Ryuk)가 맡는다.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static final int THREADS = 6;
    static final String PASSWORD = "example-password";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    // 한 요청의 HTTP 상태와 resultMsg(성공이면 null)다.
    record Outcome(int status, String message) {
        String label() {
            return message == null ? "OK" : message;
        }
    }

    static class Subject {
        final String email;
        final String token;
        final long id;

        Subject(String email, String token, long id) {
            this.email = email;
            this.token = token;
            this.id = id;
        }
    }

    // 테스트마다 고유 이메일로 가입해 다른 테스트의 일일 합계·행 잠금과 섞이지 않게 한다.
    Subject newUser(String prefix) throws Exception {
        String email = prefix + "-" + System.nanoTime() + "@example.com";
        Map<String, String> credentials = Map.of("userId", email, "password", PASSWORD);
        mvc.perform(
                        post("/user/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(signupBody(credentials))))
                .andReturn();
        MvcResult login =
                mvc.perform(
                                post("/user/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(credentials)))
                        .andReturn();
        assertEquals(200, login.getResponse().getStatus());
        String token = read(login).get("token").asText();
        long id = jdbc.queryForObject("select id from users where user_id = ?", Long.class, email);
        return new Subject(email, token, id);
    }

    // 로그인 자격에 가입 전용 필드를 더해 두 요청이 같은 이메일·비밀번호를 공유하게 한다.
    static Map<String, String> signupBody(Map<String, String> credentials) {
        var body = new HashMap<>(credentials);
        body.put("name", "동시성 사용자");
        body.put("idType", "REG_NO");
        body.put("idValue", "example-identity");
        return body;
    }

    JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    String quote(Subject user, String amount) throws Exception {
        MvcResult result =
                mvc.perform(
                                post("/transfer/quote")
                                        .header("Authorization", "Bearer " + user.token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "amount",
                                                                new BigDecimal(amount),
                                                                "targetCurrency",
                                                                "USD"))))
                        .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return read(result).at("/quote/quoteId").asText();
    }

    MvcResult request(Subject user, String quoteId, String key) throws Exception {
        var builder =
                post("/transfer/request")
                        .header("Authorization", "Bearer " + user.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("quoteId", quoteId)));
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return mvc.perform(builder).andReturn();
    }

    Outcome outcome(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        if (status == 200) {
            return new Outcome(status, null);
        }
        return new Outcome(status, read(result).path("resultMsg").asText());
    }

    JsonNode history(Subject user) throws Exception {
        MvcResult result =
                mvc.perform(get("/transfer/list").header("Authorization", "Bearer " + user.token))
                        .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return read(result);
    }

    // 접수된 행(requested_time이 채워진 행)의 수를 HTTP가 아닌 DB에서 직접 센다.
    int accepted(Subject user) {
        return jdbc.queryForObject(
                "select count(*) from remittances where user_id = ? and requested_time is not null",
                Integer.class,
                user.id);
    }

    // 모든 스레드를 시작 게이트에서 동시에 풀어 각 견적에 대한 접수 요청을 하나씩 보낸다.
    List<Outcome> race(Subject user, String key, List<String> quoteIds) throws Exception {
        int n = quoteIds.size();
        var pool = Executors.newFixedThreadPool(n);
        var ready = new CountDownLatch(n);
        var start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> jobs = new ArrayList<>();
            for (String quoteId : quoteIds) {
                jobs.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("start timed out");
                                    }
                                    return outcome(request(user, quoteId, key));
                                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (var job : jobs) {
                outcomes.add(job.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    static List<String> labels(List<Outcome> outcomes) {
        return outcomes.stream().map(Outcome::label).toList();
    }

    @Test
    void concurrentSameQuoteIsAcceptedExactlyOnce() throws Exception {
        Subject user = newUser("pg-same");
        String quoteId = quote(user, "100000");
        List<Outcome> outcomes = race(user, null, Collections.nCopies(THREADS, quoteId));
        assertEquals(1, Collections.frequency(labels(outcomes), "OK"), outcomes.toString());
        assertEquals(
                THREADS - 1,
                Collections.frequency(labels(outcomes), "ALREADY_REQUESTED"),
                outcomes.toString());
        assertTrue(
                outcomes.stream().filter(o -> o.message() != null).allMatch(o -> o.status() == 409),
                outcomes.toString());
        assertEquals(1, accepted(user));
        assertEquals(1, history(user).get("todayTransferCount").asInt());
    }

    @Test
    void concurrentDifferentQuotesCannotJointlyExceedDailyLimit() throws Exception {
        Subject user = newUser("pg-limit");
        // 테스트 환율 기준 건당 398.2 USD라 개인 한도 1000 USD 안에는 정확히 2건만 들어간다.
        List<String> quoteIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            quoteIds.add(quote(user, "400000"));
        }
        List<Outcome> outcomes = race(user, null, quoteIds);
        assertEquals(2, Collections.frequency(labels(outcomes), "OK"), outcomes.toString());
        assertEquals(
                THREADS - 2,
                Collections.frequency(labels(outcomes), "LIMIT_EXCESS"),
                outcomes.toString());
        assertTrue(
                outcomes.stream().filter(o -> o.message() != null).allMatch(o -> o.status() == 400),
                outcomes.toString());
        assertEquals(2, accepted(user));
        BigDecimal total = history(user).get("todayTransferUsdAmount").decimalValue();
        assertTrue(total.compareTo(new BigDecimal("1000")) <= 0, total.toString());
        assertEquals(0, total.compareTo(new BigDecimal("796.4")), total.toString());
    }

    @Test
    void concurrentSameKeyAndQuoteSucceedsForAllButEffectsOnce() throws Exception {
        Subject user = newUser("pg-idem");
        String quoteId = quote(user, "100000");
        List<Outcome> outcomes = race(user, "pg-same-key", Collections.nCopies(THREADS, quoteId));
        assertEquals(Collections.nCopies(THREADS, "OK"), labels(outcomes));
        assertEquals(1, accepted(user));
        // 재현된 응답은 새 행이나 시각을 만들지 않으므로 키 행도 하나, 접수 시각도 하나다.
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(distinct requested_time) from remittances"
                                + " where user_id = ? and idempotency_key = 'pg-same-key'",
                        Integer.class,
                        user.id));
        assertEquals(1, history(user).get("todayTransferCount").asInt());
    }

    @Test
    void concurrentSameKeyDifferentQuotesAcceptsOnlyOne() throws Exception {
        Subject user = newUser("pg-reuse");
        List<String> quoteIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            quoteIds.add(quote(user, "100000"));
        }
        List<Outcome> outcomes = race(user, "pg-reuse-key", quoteIds);
        assertEquals(1, Collections.frequency(labels(outcomes), "OK"), outcomes.toString());
        assertEquals(
                THREADS - 1,
                Collections.frequency(labels(outcomes), "IDEMPOTENCY_KEY_REUSED"),
                outcomes.toString());
        assertTrue(
                outcomes.stream().filter(o -> o.message() != null).allMatch(o -> o.status() == 422),
                outcomes.toString());
        assertEquals(1, accepted(user));
    }

    // 앱 풀과 무관한 평범한 JDBC 연결이 사용자 행 잠금을 쥔 채 트랜잭션을 열어 두는 도우미다.
    Connection holdUserLock(Subject user) throws Exception {
        Connection connection =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
        try (var statement =
                connection.prepareStatement("select id from users where id = ? for update")) {
            statement.setLong(1, user.id);
            assertTrue(statement.executeQuery().next());
        }
        return connection;
    }

    @Test
    void lockWaitTimeoutIsMappedFromPostgresAndRecoversAfterRelease() throws Exception {
        Subject user = newUser("pg-lock");
        String quoteId = quote(user, "100000");
        Connection lock = holdUserLock(user);
        try {
            long started = System.nanoTime();
            MvcResult timedOut = request(user, quoteId, "pg-lock-key");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(503, timedOut.getResponse().getStatus());
            assertEquals("LOCK_TIMEOUT", read(timedOut).get("resultMsg").asText());
            assertEquals("1", timedOut.getResponse().getHeader("Retry-After"));
            // lock_timeout 2000ms를 실제로 기다린 뒤 끊겨야 하며 무한 대기해서는 안 된다.
            assertTrue(elapsedMs >= 2000 && elapsedMs < 5000, "경과 ms: " + elapsedMs);
        } finally {
            lock.rollback();
            lock.close();
        }
        // 타임아웃된 시도는 접수 시각도 키도 남기지 않아야 한다.
        assertEquals(0, accepted(user));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from remittances where user_id = ?"
                                + " and idempotency_key is not null",
                        Integer.class,
                        user.id));
        assertEquals(200, request(user, quoteId, "pg-lock-key").getResponse().getStatus());
        assertEquals(1, accepted(user));
    }

    @Test
    // 앱 경로의 503 테스트가 의존하는 전제, 즉 PersistenceErrors.isLockTimeout이 기대하는 SQLState 55P03을
    // 실제 PostgreSQL이 lock_timeout 초과 시 내는지를 JDBC로 직접 증명한다.
    void postgresRaisesSqlState55P03OnLockTimeout() throws Exception {
        Subject user = newUser("pg-sqlstate");
        Connection lock = holdUserLock(user);
        try (Connection other =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = other.createStatement()) {
            statement.execute("SET lock_timeout = 500");
            var thrown =
                    assertThrows(
                            SQLException.class,
                            () ->
                                    statement.executeQuery(
                                            "select id from users where id = "
                                                    + user.id
                                                    + " for update"));
            assertEquals("55P03", thrown.getSQLState());
        } finally {
            lock.rollback();
            lock.close();
        }
    }

    @Test
    void historyIsNotBlockedByUserRowLock() throws Exception {
        Subject user = newUser("pg-history");
        assertEquals(200, request(user, quote(user, "100000"), null).getResponse().getStatus());
        Connection lock = holdUserLock(user);
        try {
            long started = System.nanoTime();
            JsonNode result = history(user);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(1, result.get("todayTransferCount").asInt());
            assertEquals(1, result.get("history").size());
            assertTrue(elapsedMs < 1000, "경과 ms: " + elapsedMs);
        } finally {
            lock.rollback();
            lock.close();
        }
    }

    @Test
    void poolRecoversAfterAllConnectionsAreTerminated() throws Exception {
        Subject user = newUser("pg-recover");
        // 접수 전에 여러 번 요청해 풀이 연결을 채우게 한다.
        for (int i = 0; i < 5; i++) {
            history(user);
        }
        String quoteId = quote(user, "100000");

        try (Connection admin =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = admin.createStatement();
                var rs =
                        statement.executeQuery(
                                "select count(pg_terminate_backend(pid)) from pg_stat_activity"
                                        + " where datname = current_database()"
                                        + " and pid <> pg_backend_pid()")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) > 0, "끊은 연결이 있어야 한다");
        }

        // 끊긴 연결을 처음 집은 요청은 실패할 수 있어, 제한된 재시도 안에 풀이 복구되는 계약만 단언한다.
        List<Integer> statuses = new ArrayList<>();
        int success = -1;
        for (int attempt = 0; attempt < 10 && success < 0; attempt++) {
            int status = request(user, quoteId, "pg-recover-key").getResponse().getStatus();
            statuses.add(status);
            if (status == 200) {
                success = attempt;
            } else {
                Thread.sleep(200);
            }
        }
        assertTrue(success >= 0, "10회 안에 복구되어야 한다: " + statuses);
        // 실패한 시도가 부분 상태를 남기지 않았으므로 접수는 정확히 한 번이다.
        assertEquals(1, accepted(user));
        assertEquals(1, history(user).get("todayTransferCount").asInt());
        // 복구 이후 요청은 모두 정상이다.
        for (int i = 0; i < 5; i++) {
            assertEquals(1, history(user).get("history").size());
        }
        assertNotNull(quote(user, "100000"));
    }
}
