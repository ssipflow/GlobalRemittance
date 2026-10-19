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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

// 운영 DB 검증 잔여 항목이다. 정상 종료(restart)가 아닌 두 장애 형태를 실제 HTTP로 확인한다.
// (A) 비정상 종료: SIGKILL(docker kill)로 PostgreSQL을 죽였다가 다시 시작한다. WAL 복구를 거쳐 올라온다.
// (B) 무응답: 컨테이너를 pause(cgroup freezer)해 TCP 연결은 살아 있지만 DB가 아무 응답도 하지 않게 한다(실제 네트워크 단절이 아니라 '무응답'을
// 대리하는 시나리오이며, 커널은 ACK를 계속한다).
// Docker가 필요하며 postgresTest로만 실행된다.
//
// 컨테이너는 호스트 포트를 고정 바인딩해 kill/start 전후로 JDBC URL이 같게 유지한다(PostgresRestartLoadTest와 같은 방식).
// 각 테스트는 finally에서 컨테이너를 반드시 unpause/start로 되돌려 다음 테스트와 정리에 영향을 주지 않는다.
@Tag("postgres")
class PostgresFailureModesTest {
    static final String PASSWORD = "example-password";
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // 일반 요청의 상한이다. 무응답 시나리오는 더 긴 상한을 따로 쓴다.
    static final Duration REQUEST_LIMIT = Duration.ofSeconds(20);
    static final int POOL_SIZE = 3;
    static final int SOCKET_TIMEOUT_SECONDS = 4;

    static PostgreSQLContainer<?> postgres;
    static int hostPort;
    static ConfigurableApplicationContext app;
    static String base;
    static JdbcTemplate jdbc;
    static HikariDataSource pooled;

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
                                "--spring.datasource.hikari.connection-timeout=2000",
                                // 무응답 시나리오를 짧게 하려는 테스트용 값이다(운영 기본 60초, 락 대기 한도보다 커야 한다).
                                "--remittance.lock-timeout-ms=1000",
                                "--remittance.db-socket-timeout-seconds=" + SOCKET_TIMEOUT_SECONDS);
        base = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
        jdbc = app.getBean(JdbcTemplate.class);
        pooled = app.getBean(HikariDataSource.class);
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
        return call(method, path, token, key, body, REQUEST_LIMIT);
    }

    static Reply call(
            String method, String path, String token, String key, Object body, Duration limit)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(limit)
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
        signup.put("name", "장애 모드 사용자");
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

    // 키-접수 시각 불일치(부분 상태) 행 수다.
    static int partialRows(Subject user) {
        return jdbc.queryForObject(
                "select count(*) from remittances where user_id = ?"
                        + " and (requested_time is null) <> (idempotency_key is null)",
                Integer.class,
                user.id());
    }

    // 장애 구간의 허용 응답: 성공이거나 Retry-After 1의 503 DB_UNAVAILABLE뿐이다.
    static void assertOutageContract(Reply reply, String context) {
        if (reply.status() == 200) {
            return;
        }
        assertEquals(503, reply.status(), context + " " + reply.body());
        assertEquals("DB_UNAVAILABLE", reply.label(), context);
        assertEquals("1", reply.retryAfter(), context);
    }

    static long ms(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    // 컨테이너가 pause 또는 중지 상태면 실행 상태로 되돌린다(정리용, 멱등).
    static void ensureRunning() {
        var client = postgres.getDockerClient();
        var state = client.inspectContainerCmd(postgres.getContainerId()).exec().getState();
        if (Boolean.TRUE.equals(state.getPaused())) {
            client.unpauseContainerCmd(postgres.getContainerId()).exec();
        } else if (!Boolean.TRUE.equals(state.getRunning())) {
            client.startContainerCmd(postgres.getContainerId()).exec();
        }
    }

    // 이력 조회와 접수가 모두 200이 될 때까지(상한) 폴링하고 걸린 ms를 돌려준다. 실패하면 -1.
    static long awaitRecovery(Subject user, String quoteId, String key, int limitSeconds)
            throws Exception {
        long begin = System.nanoTime();
        long deadline = begin + TimeUnit.SECONDS.toNanos(limitSeconds);
        while (System.nanoTime() < deadline) {
            Reply history = call("GET", "/transfer/list", user.token(), null, null);
            assertOutageContract(history, "복구 폴링 list");
            Reply reply = request(user, quoteId, key);
            assertOutageContract(reply, "복구 폴링 request");
            if (history.status() == 200 && reply.status() == 200) {
                return ms(begin);
            }
            Thread.sleep(100);
        }
        return -1;
    }

    @Test
    // SIGKILL로 DB를 죽였다가 시작해도 응답은 200/503뿐이고, 앱은 스스로 복구하며, 실패한 접수는 부분 상태를 남기지 않는다.
    void killedDatabaseIsRetryableAndSelfRecovers() throws Exception {
        Subject user = newUser("kill");
        String beforeQuote = quote(user, "100000");
        String duringQuote = quote(user, "100000");
        Reply before = request(user, beforeQuote, "kill-before");
        assertEquals(200, before.status());
        try {
            long killedAt = System.nanoTime();
            postgres.getDockerClient().killContainerCmd(postgres.getContainerId()).exec();
            // DB가 죽은 동안 새 연결이 거부되고 기존 연결은 끊긴다. 요청은 503으로 끝나야 하며 500이면 안 된다.
            List<String> down = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                long begin = System.nanoTime();
                Reply reply = request(user, duringQuote, "kill-during");
                down.add(reply.status() + "/" + ms(begin) + "ms");
                assertEquals(503, reply.status(), "DB가 죽은 동안에는 접수가 불가능하다: " + reply.body());
                assertOutageContract(reply, "kill 중 request");
            }
            Reply historyDown = call("GET", "/transfer/list", user.token(), null, null);
            assertEquals(503, historyDown.status());
            assertOutageContract(historyDown, "kill 중 list");
            System.out.println("kill 중 접수 응답(상태/경과): " + down);

            long startedAt = System.nanoTime();
            postgres.getDockerClient().startContainerCmd(postgres.getContainerId()).exec();
            long recoveryMs = awaitRecovery(user, duringQuote, "kill-during", 90);
            assertTrue(recoveryMs >= 0, "시작 후 90초 안에 복구되어야 한다");
            System.out.println(
                    "kill 복구 관찰: start 명령 후 첫 정상 응답까지 "
                            + recoveryMs
                            + "ms (kill부터 "
                            + ms(killedAt)
                            + "ms)");
            assertTrue(ms(startedAt) < 90_000);
        } finally {
            ensureRunning();
        }
        assertEquals(hostPort, postgres.getMappedPort(5432));
        // kill 전 접수는 보존되고 같은 키 재요청은 같은 응답을 재현한다.
        Reply replay = request(user, beforeQuote, "kill-before");
        assertEquals(200, replay.status());
        assertEquals(before.body(), replay.body());
        // 접수는 정확히 2건(전·복구 후)이며 부분 상태가 없고, 같은 키는 한 행뿐이다.
        assertEquals(2, accepted(user));
        assertEquals(0, partialRows(user));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from remittances where user_id = ?"
                                + " and idempotency_key = 'kill-during'",
                        Integer.class,
                        user.id()));
        Subject fresh = newUser("kill-fresh");
        assertEquals(200, request(fresh, quote(fresh, "100000"), "fresh-key").status());
    }

    @Test
    // DB가 응답 없이 멈추면(pause) 요청이 어떻게 끝나는지 관찰하고 계약(500 금지, 복구, 부분 상태 없음)을 확인한다.
    void pausedDatabaseObservedBehaviourAndRecovery() throws Exception {
        Subject user = newUser("pause");
        String freshConnQuote = quote(user, "100000");
        String pooledQuote = quote(user, "100000");
        String afterQuote = quote(user, "100000");
        // 풀에 유휴 연결이 있게 데운다.
        assertEquals(200, call("GET", "/transfer/list", user.token(), null, null).status());

        try {
            postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();

            // (1) 새 연결이 필요한 요청: 풀의 유휴 연결을 모두 폐기해 연결 생성부터 시도하게 한다.
            pooled.getHikariPoolMXBean().softEvictConnections();
            long begin = System.nanoTime();
            Reply fresh = request(user, freshConnQuote, "pause-fresh");
            long freshMs = ms(begin);
            System.out.println(
                    "pause (1) 새 연결 필요 요청: "
                            + fresh.status()
                            + " "
                            + fresh.label()
                            + " "
                            + freshMs
                            + "ms");
            assertOutageContract(fresh, "pause 새 연결");
            assertEquals(503, fresh.status());
            assertTrue(freshMs < 10_000, "새 연결 요청은 connection-timeout 근처에서 끝나야 한다: " + freshMs);

            // 폐기된 연결의 재생성도 멈춘 DB에는 불가능하므로 잠시 unpause해 풀을 되살린 뒤 (2)를 준비한다.
            postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
            assertTrue(
                    awaitRecovery(user, freshConnQuote, "pause-fresh", 30) >= 0,
                    "unpause 후 복구되어야 한다");
            long idleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (pooled.getHikariPoolMXBean().getIdleConnections() < 1
                    && System.nanoTime() < idleDeadline) {
                Thread.sleep(50);
            }
            assertTrue(pooled.getHikariPoolMXBean().getIdleConnections() >= 1);

            // (2) 풀에 연결이 이미 있는 요청: pause 후 접수를 보낸다. 쿼리를 보낸 연결에서 응답이 오지 않으므로
            // JDBC 소켓 타임아웃(4초)에서 끊겨 DB_UNAVAILABLE 503으로 끝나야 한다(타임아웃 도입 전에는 unpause까지 무한 대기했다).
            postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
            long sent = System.nanoTime();
            Reply hungReply =
                    call(
                            "POST",
                            "/transfer/request",
                            user.token(),
                            "pause-pooled",
                            Map.of("quoteId", pooledQuote),
                            Duration.ofSeconds(30));
            long pooledMs = ms(sent);
            System.out.println(
                    "pause (2) 풀 연결 요청: "
                            + hungReply.status()
                            + " "
                            + hungReply.label()
                            + " "
                            + pooledMs
                            + "ms (socketTimeout "
                            + SOCKET_TIMEOUT_SECONDS
                            + "초)");
            assertOutageContract(hungReply, "pause 풀 연결");
            assertEquals(503, hungReply.status(), hungReply.body().toString());
            assertTrue(pooledMs >= 1500 && pooledMs < 12_000, "무한 대기 없이 상한 안에 끝나야 한다: " + pooledMs);
        } finally {
            ensureRunning();
        }
        assertTrue(awaitRecovery(user, afterQuote, "pause-after", 60) >= 0);
        // 매달렸던 접수의 최종 상태와 풀 회수를 확인한다.
        long drain = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (pooled.getHikariPoolMXBean().getActiveConnections() > 0
                && System.nanoTime() < drain) {
            Thread.sleep(50);
        }
        assertEquals(0, pooled.getHikariPoolMXBean().getActiveConnections());
        assertEquals(0, partialRows(user));
        // 접수는 정확히 3건(새 연결 요청의 재시도·타임아웃 요청의 같은 키 재시도·복구 확인)이며 키마다 한 행이다.
        // 쿼리 전송 중 끊긴 요청은 커밋 전이라 롤백되므로 같은 키 재시도가 정확히 한 번 접수된다(커밋 응답 유실 구간은 검증하지 않았다).
        assertEquals(200, request(user, pooledQuote, "pause-pooled").status());
        assertEquals(3, accepted(user));
        for (String key : List.of("pause-fresh", "pause-pooled", "pause-after")) {
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "select count(*) from remittances where user_id = ?"
                                    + " and idempotency_key = ?",
                            Integer.class,
                            user.id(),
                            key));
        }
    }
}
