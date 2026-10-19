package com.nelson.remittance.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nelson.remittance.RemittanceApplication;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

// 한 PostgreSQL을 두 개의 독립 앱 인스턴스(별도 Spring 컨텍스트·서블릿 서버·커넥션 풀)가 공유할 때
// 보장이 JVM 내 락이나 캐시가 아니라 DB 행 잠금만으로 지켜지는지 실제 HTTP로 검증한다.
// 두 컨텍스트는 같은 JVM에 있어도 빈·풀·스레드를 공유하지 않으므로 인스턴스 간 경쟁을 대표한다.
// Docker가 필요하며 postgresTest로만 실행된다.
@Tag("postgres")
class PostgresMultiInstanceTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final String PASSWORD = "example-password";
    static final int THREADS = 6;

    static ConfigurableApplicationContext appA;
    static ConfigurableApplicationContext appB;
    static String baseA;
    static String baseB;
    static JdbcTemplate jdbc;

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void startInstances() {
        POSTGRES.start();
        // Flyway 마이그레이션은 A가 끝까지 마친 뒤 B를 띄워 직렬화한다. 동시 기동 경쟁은 Flyway의
        // 어드바이저리 락이 맡지만 이 테스트의 관심사(요청 경로)가 아니므로 순차 기동으로 변수를 줄인다.
        appA = launch();
        appB = launch();
        baseA = "http://localhost:" + appA.getEnvironment().getProperty("local.server.port");
        baseB = "http://localhost:" + appB.getEnvironment().getProperty("local.server.port");
        jdbc = appA.getBean(JdbcTemplate.class);
    }

    // 명령줄 인자는 프로파일 파일보다 우선하므로 test 프로파일의 H2 URL을 컨테이너 URL로 덮어쓴다.
    // JWT 서명 키와 AES 키는 test 프로파일의 같은 값을 읽어 두 인스턴스가 키를 공유하는 운영 전제를 따른다.
    static ConfigurableApplicationContext launch() {
        return new SpringApplicationBuilder(RemittanceApplication.class)
                .profiles("test", "postgres")
                .run(
                        "--server.port=0",
                        "--spring.jmx.enabled=false",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }

    @AfterAll
    static void stopInstances() {
        try {
            if (appB != null) {
                appB.close();
            }
        } finally {
            if (appA != null) {
                appA.close();
            }
        }
    }

    // HTTP 응답의 상태·본문·Retry-After이다. 성공이면 message는 null이다.
    record Reply(int status, JsonNode body, String retryAfter) {
        String label() {
            return status == 200 ? "OK" : body.path("resultMsg").asText();
        }
    }

    record Subject(String email, String token, long id) {}

    static Reply call(
            String base, String method, String path, String token, String key, Object body)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(
                    method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        HttpResponse<String> response =
                HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        return new Reply(
                response.statusCode(),
                text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    // A에서 가입·로그인한다. 토큰은 다른 인스턴스에서도 쓰이므로 발급 인스턴스를 구분하지 않는다.
    static Subject newUser(String prefix) throws Exception {
        String email = prefix + "-" + System.nanoTime() + "@example.com";
        var signup = new HashMap<String, String>();
        signup.put("userId", email);
        signup.put("password", PASSWORD);
        signup.put("name", "다중 인스턴스 사용자");
        signup.put("idType", "REG_NO");
        signup.put("idValue", "example-identity");
        call(baseA, "POST", "/user/signup", null, null, signup);
        Reply login =
                call(
                        baseA,
                        "POST",
                        "/user/login",
                        null,
                        null,
                        Map.of("userId", email, "password", PASSWORD));
        assertEquals(200, login.status());
        long id = jdbc.queryForObject("select id from users where user_id = ?", Long.class, email);
        return new Subject(email, login.body().get("token").asText(), id);
    }

    static String quote(String base, Subject user, String amount) throws Exception {
        Reply reply =
                call(
                        base,
                        "POST",
                        "/transfer/quote",
                        user.token(),
                        null,
                        Map.of("amount", new BigDecimal(amount), "targetCurrency", "USD"));
        assertEquals(200, reply.status());
        return reply.body().at("/quote/quoteId").asText();
    }

    static Reply request(String base, Subject user, String quoteId, String key) throws Exception {
        return call(
                base, "POST", "/transfer/request", user.token(), key, Map.of("quoteId", quoteId));
    }

    // 요청 i를 홀수·짝수로 A와 B에 번갈아 보내며 모든 스레드를 시작 게이트에서 동시에 푼다.
    static List<Reply> race(Subject user, String key, List<String> quoteIds) throws Exception {
        int n = quoteIds.size();
        var pool = Executors.newFixedThreadPool(n);
        var ready = new CountDownLatch(n);
        var start = new CountDownLatch(1);
        try {
            List<Future<Reply>> jobs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String base = i % 2 == 0 ? baseA : baseB;
                String quoteId = quoteIds.get(i);
                jobs.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("start timed out");
                                    }
                                    return request(base, user, quoteId, key);
                                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Reply> replies = new ArrayList<>();
            for (var job : jobs) {
                replies.add(job.get(30, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }

    static List<String> labels(List<Reply> replies) {
        return replies.stream().map(Reply::label).toList();
    }

    // 접수된 송금 행 수와 USD 합계를 HTTP가 아닌 DB에서 직접 센다.
    static int accepted(Subject user) {
        return jdbc.queryForObject(
                "select count(*) from remittances where user_id = ? and requested_time is not null",
                Integer.class,
                user.id());
    }

    // 두 인스턴스가 서로 다른 컨텍스트·풀·서버 포트이면서 같은 데이터베이스를 본다는 전제를 확인한다.
    @Test
    void instancesAreIndependentContextsOnOneDatabase() {
        assertNotSame(appA, appB);
        assertNotSame(appA.getBean(DataSource.class), appB.getBean(DataSource.class));
        assertNotEquals(baseA, baseB);
        String sql = "select inet_server_port() || '/' || current_database()";
        assertEquals(
                new JdbcTemplate(appA.getBean(DataSource.class)).queryForObject(sql, String.class),
                new JdbcTemplate(appB.getBean(DataSource.class)).queryForObject(sql, String.class));
    }

    @Test
    void sameQuoteAcrossInstancesIsAcceptedExactlyOnce() throws Exception {
        Subject user = newUser("mi-same");
        String quoteId = quote(baseA, user, "100000");
        List<Reply> replies = race(user, null, Collections.nCopies(THREADS, quoteId));
        assertEquals(1, Collections.frequency(labels(replies), "OK"), labels(replies).toString());
        assertEquals(
                THREADS - 1,
                Collections.frequency(labels(replies), "ALREADY_REQUESTED"),
                labels(replies).toString());
        assertTrue(
                replies.stream().filter(r -> r.status() != 200).allMatch(r -> r.status() == 409));
        assertEquals(1, accepted(user));
    }

    @Test
    void differentQuotesAcrossInstancesCannotJointlyExceedDailyLimit() throws Exception {
        Subject user = newUser("mi-limit");
        // 테스트 환율 기준 건당 398.2 USD라 개인 한도 1000 USD 안에는 2건만 들어간다.
        // 견적은 번갈아 두 인스턴스에서 발급해 견적 저장도 인스턴스 간에 공유됨을 함께 본다.
        List<String> quoteIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            quoteIds.add(quote(i % 2 == 0 ? baseB : baseA, user, "400000"));
        }
        List<Reply> replies = race(user, null, quoteIds);
        assertEquals(2, Collections.frequency(labels(replies), "OK"), labels(replies).toString());
        assertEquals(
                THREADS - 2,
                Collections.frequency(labels(replies), "LIMIT_EXCESS"),
                labels(replies).toString());
        assertTrue(
                replies.stream().filter(r -> r.status() != 200).allMatch(r -> r.status() == 400));
        assertEquals(2, accepted(user));
        BigDecimal total =
                jdbc.queryForObject(
                        "select coalesce(sum(target_amount), 0) from remittances"
                                + " where user_id = ? and requested_time is not null",
                        BigDecimal.class,
                        user.id());
        assertTrue(total.compareTo(new BigDecimal("1000")) <= 0, total.toString());
        assertEquals(0, total.compareTo(new BigDecimal("796.4")), total.toString());
    }

    @Test
    void sameKeyAndQuoteAcrossInstancesSucceedsForAllButEffectsOnce() throws Exception {
        Subject user = newUser("mi-idem");
        String quoteId = quote(baseA, user, "100000");
        List<Reply> replies = race(user, "mi-same-key", Collections.nCopies(THREADS, quoteId));
        assertEquals(Collections.nCopies(THREADS, "OK"), labels(replies));
        assertEquals(1, accepted(user));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from remittances where user_id = ?"
                                + " and idempotency_key = 'mi-same-key'",
                        Integer.class,
                        user.id()));
        // 재현된 응답은 인스턴스와 무관하게 같은 접수 결과여야 한다.
        assertEquals(1, replies.stream().map(r -> r.body().toString()).distinct().count());
        // 한 인스턴스가 처리한 키를 다른 인스턴스가 나중에 요청해도 같은 응답을 재현한다.
        Reply onA = request(baseA, user, quoteId, "mi-same-key");
        Reply onB = request(baseB, user, quoteId, "mi-same-key");
        assertEquals(200, onA.status());
        assertEquals(replies.get(0).body(), onA.body());
        assertEquals(replies.get(0).body(), onB.body());
        assertEquals(1, accepted(user));
    }

    // 앱 풀과 무관한 평범한 JDBC 연결이 사용자 행 잠금을 쥔 채 트랜잭션을 열어 두는 도우미다.
    // 다른 인스턴스에서 진행 중인 접수 트랜잭션이 잠금을 쥔 상황을 결정적으로 재현한다.
    static Connection holdUserLock(Subject user) throws Exception {
        Connection connection =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
        try (var statement =
                connection.prepareStatement("select id from users where id = ? for update")) {
            statement.setLong(1, user.id());
            assertTrue(statement.executeQuery().next());
        }
        return connection;
    }

    @Test
    void lockHeldElsewhereTimesOutOnBothInstancesAndRecoversAfterRelease() throws Exception {
        Subject user = newUser("mi-lock");
        String quoteId = quote(baseA, user, "100000");
        Connection lock = holdUserLock(user);
        try {
            for (String base : List.of(baseB, baseA)) {
                long started = System.nanoTime();
                Reply timedOut = request(base, user, quoteId, "mi-lock-key");
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertEquals(503, timedOut.status());
                assertEquals("LOCK_TIMEOUT", timedOut.label());
                assertEquals("1", timedOut.retryAfter());
                // lock_timeout 2000ms를 실제로 기다린 뒤 끊겨야 하며 무한 대기해서는 안 된다.
                assertTrue(elapsedMs >= 2000 && elapsedMs < 5000, "경과 ms: " + elapsedMs);
            }
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
                        user.id()));
        assertEquals(200, request(baseB, user, quoteId, "mi-lock-key").status());
        assertEquals(1, accepted(user));
        // 이후 다른 인스턴스의 같은 키 재요청은 재현이다.
        assertEquals(200, request(baseA, user, quoteId, "mi-lock-key").status());
        assertEquals(1, accepted(user));
    }

    @Test
    void tokenIssuedOnOneInstanceIsValidOnTheOther() throws Exception {
        // 두 인스턴스는 같은 remittance.jwt-secret(운영에서는 같은 REMITTANCE_JWT_SECRET)으로 서명을 검증한다.
        Subject user = newUser("mi-token");
        Reply onB = call(baseB, "GET", "/transfer/list", user.token(), null, null);
        assertEquals(200, onB.status());
        String quoteId = quote(baseB, user, "100000");
        assertEquals(200, request(baseA, user, quoteId, null).status());
        Reply onBAfter = call(baseB, "GET", "/transfer/list", user.token(), null, null);
        assertEquals(1, onBAfter.body().get("todayTransferCount").asInt());
        // 위조 토큰은 두 인스턴스 모두 거부한다.
        for (String base : List.of(baseA, baseB)) {
            assertEquals(
                    401,
                    call(base, "GET", "/transfer/list", user.token() + "x", null, null).status());
        }
    }
}
