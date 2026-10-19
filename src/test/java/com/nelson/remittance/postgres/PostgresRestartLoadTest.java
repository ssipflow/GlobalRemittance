package com.nelson.remittance.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.nelson.remittance.RemittanceApplication;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

// 운영 DB 검증의 마지막 단계다. 실제 HTTP로 (A) PostgreSQL 컨테이너를 재시작하는 동안·직후의 응답 계약과 자가 복구,
// (B) 풀 크기를 훨씬 넘는 동시 접수 폭주에서의 한도·중복·불변식을 확인한다.
// Docker가 필요하며 postgresTest로만 실행된다.
//
// 재시작 시 호스트 매핑 포트: Testcontainers의 기본 매핑은 임시 포트라 docker restart 뒤 바뀔 수 있다.
// 그래서 컨테이너 생성 시 호스트 포트를 고정(빈 포트를 잡아 바인딩)해 앱의 JDBC URL이 재시작 전후로 같게 만든다.
// 컨테이너 파일시스템은 재시작 후에도 유지되므로 데이터가 보존된다(tmpfs·볼륨 미사용).
@Tag("postgres")
class PostgresRestartLoadTest {
    static final String PASSWORD = "example-password";
    static final ObjectMapper JSON = new ObjectMapper();
    // 응답이 매달려도 테스트가 멈추지 않도록 요청마다 상한을 둔다.
    static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Duration REQUEST_LIMIT = Duration.ofSeconds(20);
    // 풀 최대 3개와 연결 획득 대기 2초: 재시작 중 요청이 최대 약 2초 안에 503으로 끝나게 하고, 폭주 때 풀 고갈이 쉽게 일어나게 한다.
    static final int POOL_SIZE = 3;

    static PostgreSQLContainer<?> postgres;
    static int hostPort;
    static ConfigurableApplicationContext app;
    static String base;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            hostPort = socket.getLocalPort();
        }
        postgres =
                new PostgreSQLContainer<>("postgres:16-alpine")
                        .withCreateContainerCmdModifier(
                                cmd ->
                                        cmd.getHostConfig()
                                                .withPortBindings(
                                                        new PortBinding(
                                                                Ports.Binding.bindPort(hostPort),
                                                                new ExposedPort(5432))));
        postgres.start();
        assertEquals(hostPort, postgres.getMappedPort(5432), "호스트 포트가 고정되어야 한다");
        app =
                new SpringApplicationBuilder(RemittanceApplication.class)
                        .profiles("test", "postgres")
                        .run(
                                "--server.port=0",
                                "--spring.jmx.enabled=false",
                                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                                "--spring.datasource.username=" + postgres.getUsername(),
                                "--spring.datasource.password=" + postgres.getPassword(),
                                "--spring.datasource.hikari.maximum-pool-size=" + POOL_SIZE,
                                "--spring.datasource.hikari.connection-timeout=2000");
        base = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
        jdbc = app.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    record Reply(int status, JsonNode body, String retryAfter) {
        String label() {
            return status == 200 ? "OK" : body.path("resultMsg").asText();
        }
    }

    record Subject(String token, long id) {}

    static Reply call(String method, String path, String token, String key, Object body)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(REQUEST_LIMIT)
                        .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> response =
                HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        return new Reply(
                response.statusCode(),
                text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    static Subject newUser(String prefix) throws Exception {
        String email = prefix + "-" + System.nanoTime() + "@example.com";
        var signup = new HashMap<String, String>();
        signup.put("userId", email);
        signup.put("password", PASSWORD);
        signup.put("name", "재시작 부하 사용자");
        signup.put("idType", "REG_NO");
        signup.put("idValue", "example-identity");
        call("POST", "/user/signup", null, null, signup);
        Reply login =
                call(
                        "POST",
                        "/user/login",
                        null,
                        null,
                        Map.of("userId", email, "password", PASSWORD));
        assertEquals(200, login.status());
        long id = jdbc.queryForObject("select id from users where user_id = ?", Long.class, email);
        return new Subject(login.body().get("token").asText(), id);
    }

    static String quote(Subject user, String amount) throws Exception {
        Reply reply =
                call(
                        "POST",
                        "/transfer/quote",
                        user.token(),
                        null,
                        Map.of("amount", new BigDecimal(amount), "targetCurrency", "USD"));
        assertEquals(200, reply.status(), reply.body().toString());
        return reply.body().at("/quote/quoteId").asText();
    }

    static Reply request(Subject user, String quoteId, String key) throws Exception {
        return call("POST", "/transfer/request", user.token(), key, Map.of("quoteId", quoteId));
    }

    static int accepted(Subject user) {
        return jdbc.queryForObject(
                "select count(*) from remittances where user_id = ? and requested_time is not null",
                Integer.class,
                user.id());
    }

    static Timestamp postmasterStart() throws Exception {
        try (Connection c =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                var rs = c.createStatement().executeQuery("select pg_postmaster_start_time()")) {
            rs.next();
            return rs.getTimestamp(1);
        }
    }

    // 재시작 구간의 허용 응답: 성공이거나 Retry-After 1의 503 DB_UNAVAILABLE뿐이다.
    static void assertRestartContract(Reply reply, String context) {
        if (reply.status() == 200) {
            return;
        }
        assertEquals(503, reply.status(), context + " " + reply.body());
        assertEquals("DB_UNAVAILABLE", reply.label(), context);
        assertEquals("1", reply.retryAfter(), context);
    }

    @Test
    // 재시작 전 접수는 보존되고, 재시작 중 요청은 500 없이 200/503이며, 앱은 스스로 복구하고, 실패한 접수는 부분 상태를 남기지 않는다.
    void databaseRestartIsRetryableAndSelfRecovers() throws Exception {
        Subject user = newUser("restart");
        String beforeQuote = quote(user, "100000");
        String duringQuote = quote(user, "100000");
        Reply before = request(user, beforeQuote, "restart-before");
        assertEquals(200, before.status());
        Timestamp startedBefore = postmasterStart();

        var restarted = new AtomicBoolean(false);
        var pool = Executors.newFixedThreadPool(2);
        List<String> observed = new ArrayList<>();
        try {
            // 재시작은 별도 스레드에서 실행하고, 그동안 같은 키로 접수를 재시도하며 이력 조회도 섞는다.
            Future<?> restart =
                    pool.submit(
                            () -> {
                                postgres.getDockerClient()
                                        .restartContainerCmd(postgres.getContainerId())
                                        .withTimeout(10)
                                        .exec();
                                restarted.set(true);
                                return null;
                            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            boolean recovered = false;
            // 재시작 명령이 끝난 뒤 이력 조회와 접수가 모두 200이 될 때까지(상한 90초) 반복한다.
            // 재시작 이전의 성공은 복구 증거가 아니므로 반복 시작 시점의 재시작 완료 여부를 따로 잡는다.
            while (System.nanoTime() < deadline) {
                boolean afterRestart = restarted.get();
                Reply history = call("GET", "/transfer/list", user.token(), null, null);
                assertRestartContract(history, "list");
                Reply reply = request(user, duringQuote, "restart-during");
                assertRestartContract(reply, "request");
                observed.add(history.status() + "/" + reply.status());
                if (afterRestart && reply.status() == 200 && history.status() == 200) {
                    recovered = true;
                    break;
                }
                Thread.sleep(50);
            }
            restart.get(30, TimeUnit.SECONDS);
            assertTrue(recovered, "재시작 후 90초 안에 복구되어야 한다: " + observed);
            System.out.println("재시작 관찰(이력/접수 상태): " + observed);
            // 재시작 구간이 실제로 요청에 영향을 줬어야 시나리오가 성립한다(재시작은 수 초, 폴링은 50ms 간격).
            assertTrue(
                    observed.stream().anyMatch(o -> o.contains("503")),
                    "재시작 중 503이 한 번은 관찰되어야 한다: " + observed);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(hostPort, postgres.getMappedPort(5432), "재시작 뒤에도 포트가 같아야 한다");
        Timestamp startedAfter = postmasterStart();
        assertTrue(startedAfter.after(startedBefore), "PostgreSQL이 실제로 재시작되어야 한다");
        // 복구 뒤 새 사용자 흐름도 정상이다.
        Subject fresh = newUser("restart-fresh");
        assertEquals(200, request(fresh, quote(fresh, "100000"), "fresh-key").status());
        // 재시작 전 접수는 보존되고 같은 키 재요청은 같은 응답을 재현한다.
        Reply replay = request(user, beforeQuote, "restart-before");
        assertEquals(200, replay.status());
        assertEquals(before.body(), replay.body());
        // 사용자 접수는 정확히 2건(전·중)이며 키는 접수된 행에만 있다(부분 상태 없음).
        assertEquals(2, accepted(user));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from remittances where user_id = ?"
                                + " and (requested_time is null) <> (idempotency_key is null)",
                        Integer.class,
                        user.id()));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from remittances where user_id = ?"
                                + " and idempotency_key = 'restart-during'",
                        Integer.class,
                        user.id()));
    }

    static final int USERS = 8;
    static final int QUOTES_PER_USER = 16;
    static final int THREADS = 32;

    @Test
    // 풀(3)과 사용자 수를 넘는 32스레드가 사용자당 16개 견적을 각각 두 번(서로 다른 키) 접수한다.
    void heavyConcurrentRequestsKeepLimitAndInvariants() throws Exception {
        List<Subject> users = new ArrayList<>();
        Map<Long, List<String>> quotes = new HashMap<>();
        for (int u = 0; u < USERS; u++) {
            Subject user = newUser("load" + u);
            users.add(user);
            List<String> ids = new ArrayList<>();
            for (int q = 0; q < QUOTES_PER_USER; q++) {
                ids.add(quote(user, "100000"));
            }
            quotes.put(user.id(), ids);
        }

        record Job(Subject user, String quoteId, String key) {}
        List<Job> jobs = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (int q = 0; q < QUOTES_PER_USER; q++) {
                for (Subject user : users) {
                    jobs.add(
                            new Job(
                                    user,
                                    quotes.get(user.id()).get(q),
                                    "load-" + user.id() + "-" + q + "-" + round));
                }
            }
        }
        var pool = Executors.newFixedThreadPool(THREADS);
        var start = new CountDownLatch(1);
        Map<String, Integer> firstLabels = new TreeMap<>();
        // 키별 최종 결과: 503이면 폭주가 가라앉은 뒤 같은 키로 재시도한다(재시도 안내 계약).
        Map<Job, Reply> finals = new HashMap<>();
        try {
            Map<Job, Future<Reply>> futures = new HashMap<>();
            for (Job job : jobs) {
                futures.put(
                        job,
                        pool.submit(
                                () -> {
                                    start.await();
                                    return request(job.user(), job.quoteId(), job.key());
                                }));
            }
            start.countDown();
            for (var entry : futures.entrySet()) {
                Reply reply = entry.getValue().get(60, TimeUnit.SECONDS);
                firstLabels.merge(reply.label() + "/" + reply.status(), 1, Integer::sum);
                finals.put(entry.getKey(), reply);
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("폭주 1차 응답 분포: " + firstLabels);
        int retried = 0;
        for (var entry : finals.entrySet()) {
            Reply reply = entry.getValue();
            // 500(UNKNOWN_ERROR)은 어떤 경우에도 허용하지 않는다.
            assertTrue(reply.status() != 500, "UNKNOWN_ERROR: " + reply.body());
            if (reply.status() == 503) {
                assertEquals("1", reply.retryAfter());
                assertTrue(
                        List.of("LOCK_TIMEOUT", "DB_UNAVAILABLE").contains(reply.label()),
                        reply.label());
                Job job = entry.getKey();
                Reply again = null;
                for (int attempt = 0; attempt < 10; attempt++) {
                    again = request(job.user(), job.quoteId(), job.key());
                    if (again.status() != 503) {
                        break;
                    }
                    // Retry-After: 1 안내보다 짧게 쉬며 재시도해 같은 병목에 즉시 다시 몰리지 않게 한다.
                    Thread.sleep(200);
                }
                entry.setValue(again);
                retried++;
            }
        }
        System.out.println("503 후 재시도한 요청 수: " + retried);

        // 같은 견적을 서로 다른 키로 두 번 보냈으므로 견적마다 200은 많아야 한 번이다. 사용자별 200 수를 센다.
        Map<Long, Integer> okByUser = new HashMap<>();
        for (var entry : finals.entrySet()) {
            Reply reply = entry.getValue();
            String label = reply.label();
            assertTrue(
                    label.equals("OK")
                            || label.equals("LIMIT_EXCESS")
                            || label.equals("ALREADY_REQUESTED"),
                    "정의되지 않은 최종 응답: " + reply.status() + " " + label);
            if (reply.status() == 200) {
                okByUser.merge(entry.getKey().user().id(), 1, Integer::sum);
            }
        }
        // DB 불변식: 사용자별 일일 합계(usd_amount) 한도, 키 중복 없음, 응답의 200 수와 접수 행 수 일치.
        for (Subject user : users) {
            BigDecimal total =
                    jdbc.queryForObject(
                            "select coalesce(sum(usd_amount), 0) from remittances"
                                    + " where user_id = ? and requested_time is not null",
                            BigDecimal.class,
                            user.id());
            assertTrue(total.compareTo(new BigDecimal("1000")) <= 0, total.toString());
            // 한도가 실제로 채워졌다: 같은 크기의 한 건을 더하면 초과해야 한다(불필요한 거절이 없다).
            BigDecimal one =
                    jdbc.queryForObject(
                            "select min(usd_amount) from remittances where user_id = ?",
                            BigDecimal.class,
                            user.id());
            assertTrue(total.add(one).compareTo(new BigDecimal("1000")) > 0, total + " " + one);
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "select count(*) from (select idempotency_key from remittances"
                                    + " where user_id = ? and idempotency_key is not null"
                                    + " group by idempotency_key having count(*) > 1) d",
                            Integer.class,
                            user.id()));
            assertEquals(okByUser.getOrDefault(user.id(), 0), accepted(user), "응답과 행 수 불일치");
            System.out.println("사용자 " + user.id() + " 접수 " + accepted(user) + "건 합계 " + total);
        }

        // 풀 고갈 뒤에도 풀이 정상으로 돌아와 새 요청이 성공한다.
        HikariDataSource pooled = app.getBean(HikariDataSource.class);
        long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pooled.getHikariPoolMXBean().getActiveConnections() > 0
                && System.nanoTime() < drainDeadline) {
            Thread.sleep(50);
        }
        assertEquals(0, pooled.getHikariPoolMXBean().getActiveConnections());
        assertEquals(0, pooled.getHikariPoolMXBean().getThreadsAwaitingConnection());
        Subject after = newUser("load-after");
        assertEquals(200, request(after, quote(after, "100000"), "after-key").status());
    }

    @Test
    // 풀의 연결을 모두 점유해 고갈시키면 요청은 무한 대기하지 않고 connection-timeout(2초) 뒤 503 DB_UNAVAILABLE이 되며,
    // 연결을 돌려주면 같은 요청이 성공한다. 고갈 중 실패한 접수는 부분 상태를 남기지 않는다.
    void poolExhaustionIsBoundedAndRecovers() throws Exception {
        Subject user = newUser("exhaust");
        String quoteId = quote(user, "100000");
        HikariDataSource pooled = app.getBean(HikariDataSource.class);
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < POOL_SIZE; i++) {
                held.add(pooled.getConnection());
            }
            long started = System.nanoTime();
            Reply starved = request(user, quoteId, "exhaust-key");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(503, starved.status(), starved.body().toString());
            assertEquals("DB_UNAVAILABLE", starved.label());
            assertEquals("1", starved.retryAfter());
            assertTrue(elapsedMs >= 1500 && elapsedMs < 10000, "경과 ms: " + elapsedMs);
            System.out.println("풀 고갈 503 경과 ms: " + elapsedMs);
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
        assertEquals(0, accepted(user));
        assertEquals(200, request(user, quoteId, "exhaust-key").status());
        assertEquals(1, accepted(user));
    }
}
