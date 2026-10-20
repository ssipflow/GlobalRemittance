package com.nelson.remittance.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nelson.remittance.RemittanceApplication;
import com.zaxxer.hikari.HikariDataSource;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

// 네트워크 분단 검증이다. 컨테이너 pause·kill(PostgresFailureModesTest)이나 테스트 내 TCP
// 프록시(PostgresCommitResponseLossTest)가 아니라
// 실제 Toxiproxy를 앱과 PostgreSQL 사이에 두고 네트워크 결함(블랙홀, RST, 지연, 응답 방향 단절)을 주입한다.
// Docker가 필요하며 postgresTest로만 실행된다.
//
// 앱의 JDBC는 Toxiproxy 매핑 포트를 거쳐 같은 Docker 네트워크의 PostgreSQL로 간다. 상태 확인은 프록시를 거치지 않는
// 컨테이너 직접 연결(direct)로 한다. 각 시나리오는 독립이며 finally에서 toxic 제거와 프록시 enable로 반드시 복구한다.
@Tag("postgres")
class PostgresNetworkPartitionTest {
    static final String PASSWORD = "example-password";
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Duration REQUEST_LIMIT = Duration.ofSeconds(30);
    static final int POOL_SIZE = 3;
    static final int SOCKET_TIMEOUT_SECONDS = 4;

    static Network network;
    static PostgreSQLContainer<?> postgres;
    static ToxiproxyContainer toxiproxy;
    static ToxiproxyContainer.ContainerProxy proxy;
    // enable/disable은 컨테이너 래퍼에 없어 제어 API 클라이언트로 같은 프록시를 다룬다.
    static eu.rekawek.toxiproxy.Proxy control;
    static ConfigurableApplicationContext app;
    static String base;
    static JdbcTemplate direct;
    static HikariDataSource pooled;

    @BeforeAll
    static void start() throws Exception {
        network = Network.newNetwork();
        postgres =
                new PostgreSQLContainer<>("postgres:16-alpine")
                        .withNetwork(network)
                        .withNetworkAliases("postgres");
        toxiproxy =
                new ToxiproxyContainer(
                                DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.9.0")
                                        .asCompatibleSubstituteFor("shopify/toxiproxy"))
                        .withNetwork(network);
        postgres.start();
        toxiproxy.start();
        proxy = toxiproxy.getProxy("postgres", 5432);
        control =
                new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort())
                        .getProxy(proxy.getName());
        String proxiedUrl =
                "jdbc:postgresql://"
                        + proxy.getContainerIpAddress()
                        + ":"
                        + proxy.getProxyPort()
                        + "/"
                        + postgres.getDatabaseName()
                        + "?sslmode=disable&gssEncMode=disable";
        app =
                new SpringApplicationBuilder(RemittanceApplication.class)
                        .profiles("test", "postgres")
                        .run(
                                "--server.port=0",
                                "--spring.jmx.enabled=false",
                                "--spring.datasource.url=" + proxiedUrl,
                                "--spring.datasource.username=" + postgres.getUsername(),
                                "--spring.datasource.password=" + postgres.getPassword(),
                                "--spring.datasource.hikari.maximum-pool-size=" + POOL_SIZE,
                                "--spring.datasource.hikari.connection-timeout=2000",
                                // 단절 시나리오를 짧게 하려는 테스트용 값이다(운영 기본 60초, 락 대기 한도보다 커야 한다).
                                "--remittance.lock-timeout-ms=1000",
                                "--remittance.db-socket-timeout-seconds=" + SOCKET_TIMEOUT_SECONDS);
        base = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
        pooled = app.getBean(HikariDataSource.class);
        direct =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword()));
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        if (toxiproxy != null) {
            toxiproxy.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (network != null) {
            network.close();
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
        signup.put("name", "네트워크 분단 사용자");
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
        long id =
                direct.queryForObject("select id from users where user_id = ?", Long.class, email);
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

    static int rowsForKey(Subject user, String key) {
        return direct.queryForObject(
                "select count(*) from remittances where user_id = ? and idempotency_key = ?",
                Integer.class,
                user.id(),
                key);
    }

    static int accepted(Subject user) {
        return direct.queryForObject(
                "select count(*) from remittances where user_id = ? and requested_time is not null",
                Integer.class,
                user.id());
    }

    // 키-접수 시각 불일치(부분 상태) 행 수다.
    static int partialRows(Subject user) {
        return direct.queryForObject(
                "select count(*) from remittances where user_id = ?"
                        + " and (requested_time is null) <> (idempotency_key is null)",
                Integer.class,
                user.id());
    }

    static long ms(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    // 500 등 정의되지 않은 응답을 숨기지 않기 위해 상태·본문·소요를 모두 메시지에 담는다.
    static void assertDefinedUnavailable(Reply reply, String context) {
        String detail = context + " status=" + reply.status() + " body=" + reply.body();
        assertEquals(503, reply.status(), "정의된 503이어야 한다(500 금지): " + detail);
        assertEquals("DB_UNAVAILABLE", reply.label(), detail);
        assertEquals("1", reply.retryAfter(), detail);
    }

    // 장애 구간의 허용 응답: 성공이거나 Retry-After 1의 503 DB_UNAVAILABLE뿐이다.
    static void assertOutageContract(Reply reply, String context) {
        if (reply.status() != 200) {
            assertDefinedUnavailable(reply, context);
        }
    }

    // 모든 toxic을 제거하고 프록시를 enable한다(정리용, 멱등).
    static void restore() throws IOException {
        for (var toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
        control.enable();
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

    // 풀에 유휴 연결이 생길 때까지(상한) 기다린다. 풀 연결이 필요한 시나리오의 준비 단계다.
    static void awaitIdleConnection() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (pooled.getHikariPoolMXBean().getIdleConnections() < 1
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(pooled.getHikariPoolMXBean().getIdleConnections() >= 1, "유휴 연결이 있어야 한다");
    }

    // 시나리오 끝의 공통 확인: 활성 연결 회수, 부분 상태 없음.
    static void assertDrained(Subject user) throws Exception {
        long drain = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (pooled.getHikariPoolMXBean().getActiveConnections() > 0
                && System.nanoTime() < drain) {
            Thread.sleep(50);
        }
        assertEquals(0, pooled.getHikariPoolMXBean().getActiveConnections());
        assertEquals(0, partialRows(user));
    }

    @Test
    // 블랙홀: 양방향 데이터를 버리지만 연결은 열려 있다. 풀 연결 요청은 socketTimeout 근처, 새 연결 요청은 connection-timeout 근처에서
    // 503이다.
    void blackholeIsBoundedAndRecovers() throws Exception {
        Subject user = newUser("hole");
        String pooledQuote = quote(user, "100000");
        String freshQuote = quote(user, "100000");
        assertEquals(200, call("GET", "/transfer/list", user.token(), null, null).status());
        awaitIdleConnection();
        try {
            proxy.toxics().timeout("hole-up", ToxicDirection.UPSTREAM, 0);
            proxy.toxics().timeout("hole-down", ToxicDirection.DOWNSTREAM, 0);

            // (1) 풀에 연결이 있는 요청: 쿼리를 보낸 연결에서 응답이 오지 않아 소켓 타임아웃(4초)에서 끊긴다.
            long begin = System.nanoTime();
            Reply hung = request(user, pooledQuote, "hole-pooled");
            long pooledMs = ms(begin);
            System.out.println(
                    "블랙홀 (1) 풀 연결 요청: "
                            + hung.status()
                            + " "
                            + hung.label()
                            + " "
                            + pooledMs
                            + "ms");
            assertDefinedUnavailable(hung, "블랙홀 풀 연결");
            assertTrue(
                    pooledMs >= 3000 && pooledMs <= 12_000, "socketTimeout 근처여야 한다: " + pooledMs);

            // (2) 새 연결이 필요한 요청: 풀을 비워 연결 생성부터 시도하게 한다. 시작 메시지 응답이 없어 connection-timeout(2초)에서 끝난다.
            pooled.getHikariPoolMXBean().softEvictConnections();
            begin = System.nanoTime();
            Reply fresh = request(user, freshQuote, "hole-fresh");
            long freshMs = ms(begin);
            System.out.println(
                    "블랙홀 (2) 새 연결 요청: "
                            + fresh.status()
                            + " "
                            + fresh.label()
                            + " "
                            + freshMs
                            + "ms");
            assertDefinedUnavailable(fresh, "블랙홀 새 연결");
            assertTrue(
                    freshMs >= 1500 && freshMs <= 8000, "connection-timeout 근처여야 한다: " + freshMs);
            // 서버에는 아무것도 닿지 않았다.
            assertEquals(0, rowsForKey(user, "hole-pooled"));
            assertEquals(0, rowsForKey(user, "hole-fresh"));
        } finally {
            restore();
        }
        long recoveryMs = awaitRecovery(user, pooledQuote, "hole-pooled", 60);
        System.out.println("블랙홀 복구: toxic 제거 후 첫 정상 응답까지 " + recoveryMs + "ms");
        assertTrue(recoveryMs >= 0, "toxic 제거 후 60초 안에 복구되어야 한다");
        assertDrained(user);
        // 같은 키 재시도는 정확히 한 행이다.
        assertEquals(200, request(user, pooledQuote, "hole-pooled").status());
        assertEquals(1, rowsForKey(user, "hole-pooled"));
        assertEquals(200, request(user, freshQuote, "hole-fresh").status());
        assertEquals(1, rowsForKey(user, "hole-fresh"));
        assertEquals(2, accepted(user));
    }

    @Test
    // reset_peer: 연결이 RST로 끊긴다. 503이 빨리 오고, toxic 제거 후 복구된다.
    void connectionResetFailsFastAndRecovers() throws Exception {
        Subject user = newUser("reset");
        String quoteId = quote(user, "100000");
        assertEquals(200, call("GET", "/transfer/list", user.token(), null, null).status());
        awaitIdleConnection();
        try {
            proxy.toxics().resetPeer("reset-down", ToxicDirection.DOWNSTREAM, 0);
            long begin = System.nanoTime();
            Reply reply = request(user, quoteId, "reset-key");
            long elapsed = ms(begin);
            System.out.println(
                    "reset_peer 요청: "
                            + reply.status()
                            + " "
                            + reply.label()
                            + " "
                            + elapsed
                            + "ms");
            assertDefinedUnavailable(reply, "reset_peer");
            assertTrue(elapsed < 3500, "RST는 타임아웃을 기다리지 않고 빨리 끝나야 한다: " + elapsed);
            assertEquals(0, rowsForKey(user, "reset-key"));
        } finally {
            restore();
        }
        long recoveryMs = awaitRecovery(user, quoteId, "reset-key", 60);
        System.out.println("reset_peer 복구: " + recoveryMs + "ms");
        assertTrue(recoveryMs >= 0, "복구되어야 한다");
        assertDrained(user);
        assertEquals(1, rowsForKey(user, "reset-key"));
        assertEquals(1, accepted(user));
    }

    @Test
    // 프록시 disable: 기존 연결이 끊기고 새 연결은 거부된다. 503이 빨리 오고 enable 후 복구된다.
    void disabledProxyFailsFastAndRecovers() throws Exception {
        Subject user = newUser("disable");
        String quoteId = quote(user, "100000");
        assertEquals(200, call("GET", "/transfer/list", user.token(), null, null).status());
        awaitIdleConnection();
        try {
            control.disable();
            long begin = System.nanoTime();
            Reply reply = request(user, quoteId, "disable-key");
            long elapsed = ms(begin);
            System.out.println(
                    "프록시 disable 요청: "
                            + reply.status()
                            + " "
                            + reply.label()
                            + " "
                            + elapsed
                            + "ms");
            assertDefinedUnavailable(reply, "프록시 disable");
            assertTrue(elapsed < 4500, "연결 거부/끊김은 빨리 끝나야 한다: " + elapsed);
            assertEquals(0, rowsForKey(user, "disable-key"));
        } finally {
            restore();
        }
        long recoveryMs = awaitRecovery(user, quoteId, "disable-key", 60);
        System.out.println("프록시 enable 복구: " + recoveryMs + "ms");
        assertTrue(recoveryMs >= 0, "복구되어야 한다");
        assertDrained(user);
        assertEquals(1, rowsForKey(user, "disable-key"));
    }

    @Test
    // 지연: socketTimeout 미만이면 느리지만 200, 초과하면 503 DB_UNAVAILABLE이고 이후 복구된다.
    void latencyBelowAndAboveSocketTimeout() throws Exception {
        Subject user = newUser("latency");
        String slowQuote = quote(user, "100000");
        String overQuote = quote(user, "100000");
        assertEquals(200, call("GET", "/transfer/list", user.token(), null, null).status());
        awaitIdleConnection();
        try {
            // 서버→클라이언트 응답마다 1초 지연이다. 읽기 한 번이 4초 미만이므로 소켓 타임아웃에 걸리지 않는다.
            proxy.toxics().latency("slow", ToxicDirection.DOWNSTREAM, 1000);
            long begin = System.nanoTime();
            Reply slow = request(user, slowQuote, "latency-slow");
            long slowMs = ms(begin);
            System.out.println(
                    "지연 1000ms 요청: " + slow.status() + " " + slow.label() + " " + slowMs + "ms");
            assertEquals(200, slow.status(), "지연이 socketTimeout 미만이면 성공해야 한다: " + slow.body());
            assertTrue(slowMs >= 1000, "지연이 실제로 반영되어야 한다: " + slowMs);
            assertTrue(slowMs <= 25_000, "상한 안에 끝나야 한다: " + slowMs);
            assertEquals(1, rowsForKey(user, "latency-slow"));

            // 지연을 socketTimeout(4초) 위로 올린다.
            restore();
            awaitIdleConnection();
            proxy.toxics().latency("over", ToxicDirection.DOWNSTREAM, 6000);
            begin = System.nanoTime();
            Reply over = request(user, overQuote, "latency-over");
            long overMs = ms(begin);
            System.out.println(
                    "지연 6000ms 요청: " + over.status() + " " + over.label() + " " + overMs + "ms");
            assertDefinedUnavailable(over, "지연 6000ms");
            assertTrue(overMs >= 3000 && overMs <= 12_000, "socketTimeout 근처여야 한다: " + overMs);
        } finally {
            restore();
        }
        long recoveryMs = awaitRecovery(user, overQuote, "latency-over", 60);
        System.out.println("지연 복구: " + recoveryMs + "ms");
        assertTrue(recoveryMs >= 0, "복구되어야 한다");
        assertDrained(user);
        assertEquals(1, rowsForKey(user, "latency-over"));
        assertEquals(2, accepted(user));
        for (String key : List.of("latency-slow", "latency-over")) {
            assertEquals(1, rowsForKey(user, key));
        }
    }

    @Test
    // 응답 방향만 단절: 서버는 요청을 받지만 응답이 클라이언트에 닿지 않는다. 클라이언트는 socketTimeout 뒤 503을 받고,
    // 복구 후 같은 키 재시도는 정확히 한 행으로 200이다. DB에 커밋됐는지는 관찰해서 기록한다.
    void lostResponsesDirectionOnly() throws Exception {
        Subject user = newUser("down");
        String quoteId = quote(user, "100000");
        assertEquals(200, call("GET", "/transfer/list", user.token(), null, null).status());
        awaitIdleConnection();
        int committedDuringPartition;
        try {
            proxy.toxics().timeout("down-hole", ToxicDirection.DOWNSTREAM, 0);
            long begin = System.nanoTime();
            Reply first = request(user, quoteId, "down-key");
            long firstMs = ms(begin);
            // 서버 쪽에서 요청이 처리됐는지 본다(앱이 소켓을 닫으면 서버는 남은 작업을 롤백하므로 0 또는 1이다).
            committedDuringPartition = rowsForKey(user, "down-key");
            System.out.println(
                    "응답 단절 첫 응답: "
                            + first.status()
                            + " "
                            + first.label()
                            + " "
                            + firstMs
                            + "ms, DB 반영 행="
                            + committedDuringPartition);
            assertDefinedUnavailable(first, "응답 방향 단절");
            assertTrue(firstMs >= 3000 && firstMs <= 12_000, "socketTimeout 근처여야 한다: " + firstMs);
            // 첫 쿼리 응답이 막혀 COMMIT 전에 끊기므로 결정적으로 0이다(커밋 후 응답 유실은 PostgresCommitResponseLossTest가
            // 다룬다).
            assertEquals(0, committedDuringPartition, "COMMIT 전에 끊겨 반영 행이 없어야 한다");
        } finally {
            restore();
        }
        long recoveryMs = awaitRecovery(user, quoteId, "down-key", 60);
        System.out.println("응답 단절 복구: " + recoveryMs + "ms");
        assertTrue(recoveryMs >= 0, "복구되어야 한다");
        assertDrained(user);
        Reply retry = request(user, quoteId, "down-key");
        assertEquals(200, retry.status(), retry.body().toString());
        assertEquals(1, rowsForKey(user, "down-key"));
        assertEquals(1, accepted(user));
    }
}
