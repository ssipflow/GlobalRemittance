package com.nelson.remittance.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nelson.remittance.RemittanceApplication;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

// 커밋 응답 유실 검증이다. DB는 커밋했는데 앱이 그 사실을 모르는 구간(COMMIT 응답이 오기 전에 연결이 끊김)에서
// 앱이 정의된 오류(503 DB_UNAVAILABLE)로 끝나고, 같은 Idempotency-Key 재시도가 정확히 한 번만 반영되는지 확인한다.
// Docker가 필요하며 postgresTest로만 실행된다.
//
// 앱의 JDBC는 테스트 안의 TCP 프록시를 거쳐 컨테이너로 간다. 프록시는 클라이언트→서버 스트림을 PostgreSQL 프로토콜
// 메시지 단위로 읽어 simple Query "COMMIT"을 찾고, 무장(arm)된 한 번만 결함을 주입한다. 시간이나 경합에 기대지 않고
// 정확히 COMMIT 한 개에서 결정적으로 발생한다.
//  - AFTER_SERVER_COMMIT: COMMIT을 서버로 전달하고, 서버 응답을 읽어 버린 뒤 양쪽 소켓을 닫는다(커밋됨, 응답 유실).
//  - BEFORE_SERVER_COMMIT: COMMIT을 서버로 전달하지 않고 양쪽 소켓을 닫는다(서버는 연결 종료로 롤백한다).
@Tag("postgres")
class PostgresCommitResponseLossTest {
    static final String PASSWORD = "example-password";
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Duration REQUEST_LIMIT = Duration.ofSeconds(20);

    enum Fault {
        NONE,
        AFTER_SERVER_COMMIT,
        BEFORE_SERVER_COMMIT
    }

    // 결함 주입 TCP 프록시다. 모든 스레드와 소켓은 close()에서 정리한다.
    static final class FaultProxy implements AutoCloseable {
        private final ServerSocket listener;
        private final String targetHost;
        private final int targetPort;
        private final AtomicReference<Fault> armed = new AtomicReference<>(Fault.NONE);
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private volatile boolean closed;
        private volatile int injected;

        FaultProxy(String targetHost, int targetPort) throws IOException {
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::acceptLoop, "fault-proxy-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        void arm(Fault fault) {
            injected = 0;
            armed.set(fault);
        }

        int injected() {
            return injected;
        }

        private void acceptLoop() {
            while (!closed) {
                try {
                    Socket client = listener.accept();
                    Socket server = new Socket(targetHost, targetPort);
                    sockets.add(client);
                    sockets.add(server);
                    var discard = new java.util.concurrent.atomic.AtomicBoolean();
                    start(() -> clientToServer(client, server, discard), "fault-proxy-c2s");
                    start(() -> serverToClient(client, server, discard), "fault-proxy-s2c");
                } catch (IOException e) {
                    if (closed) {
                        return;
                    }
                }
            }
        }

        private static void start(Runnable body, String name) {
            Thread thread = new Thread(body, name);
            thread.setDaemon(true);
            thread.start();
        }

        // 시작 메시지(타입 바이트 없음) 하나를 통과시킨 뒤 타입 바이트 + int32 길이 메시지를 하나씩 읽는다.
        // JDBC URL이 sslmode=disable이라 SSLRequest 협상은 없다.
        private void clientToServer(
                Socket client, Socket server, java.util.concurrent.atomic.AtomicBoolean discard) {
            try {
                DataInputStream in = new DataInputStream(client.getInputStream());
                OutputStream out = server.getOutputStream();
                int startupLength = in.readInt();
                byte[] startup = new byte[startupLength - 4];
                in.readFully(startup);
                out.write(intBytes(startupLength));
                out.write(startup);
                out.flush();
                // pgjdbc는 COMMIT을 확장 프로토콜(Parse "COMMIT" ... Sync)이나 simple Query로 보낸다. 둘 다 찾는다.
                // 확장 프로토콜은 Sync까지 메시지를 모아 두었다가 결함 종류에 따라 전달하거나 버린다.
                // pgjdbc는 COMMIT을 이름 있는 prepared statement로 한 번 Parse한 뒤 Bind/Execute/Sync로 재사용한다.
                Set<String> commitStatements = new HashSet<>();
                ByteArrayOutputStream held = null;
                Fault pending = Fault.NONE;
                while (true) {
                    int type = in.read();
                    if (type < 0) {
                        return;
                    }
                    int length = in.readInt();
                    byte[] body = new byte[length - 4];
                    in.readFully(body);
                    if (type == 'P') {
                        // 무명 문장("")은 재정의되므로 COMMIT이 아닌 Parse가 오면 목록에서 뺀다.
                        if (isCommitSql(secondField(body))) {
                            commitStatements.add(firstField(body));
                        } else {
                            commitStatements.remove(firstField(body));
                        }
                    }
                    boolean commitStart =
                            type == 'Q'
                                    ? isCommitSql(firstField(body))
                                    : type == 'B' && commitStatements.contains(secondField(body));
                    if (held == null && commitStart) {
                        pending = armed.getAndSet(Fault.NONE);
                        if (pending != Fault.NONE) {
                            held = new ByteArrayOutputStream();
                        }
                    }
                    if (held != null) {
                        held.write(type);
                        held.write(intBytes(length));
                        held.write(body);
                        // simple Query는 그 자체로, 확장 프로토콜은 Sync에서 하나의 커밋 단위가 끝난다.
                        if (type == 'Q' || type == 'S') {
                            injected++;
                            if (pending == Fault.BEFORE_SERVER_COMMIT) {
                                closeBoth(client, server);
                                return;
                            }
                            // 서버로 전달하되, 서버 응답은 클라이언트에 보내지 않고 읽는 즉시 연결을 끊는다.
                            discard.set(true);
                            out.write(held.toByteArray());
                            out.flush();
                            held = null;
                            pending = Fault.NONE;
                        }
                        continue;
                    }
                    out.write(type);
                    out.write(intBytes(length));
                    out.write(body);
                    out.flush();
                }
            } catch (IOException e) {
                closeBoth(client, server);
            }
        }

        private void serverToClient(
                Socket client, Socket server, java.util.concurrent.atomic.AtomicBoolean discard) {
            try {
                InputStream in = server.getInputStream();
                OutputStream out = client.getOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (discard.get()) {
                        // 커밋에 대한 서버 응답을 읽었고 버린다. 앱은 응답을 받지 못한 채 연결 종료를 본다.
                        closeBoth(client, server);
                        return;
                    }
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException e) {
                // 연결 종료는 정상 경로다.
            } finally {
                closeBoth(client, server);
            }
        }

        private static boolean isCommitSql(String sql) {
            return "COMMIT".equalsIgnoreCase(sql.trim());
        }

        // 본문 앞쪽의 NUL 종료 문자열 필드(Parse: 이름·SQL, Bind: 포털·문장 이름, Query: SQL)를 읽는다.
        private static String firstField(byte[] body) {
            return field(body, 0);
        }

        private static String secondField(byte[] body) {
            return field(body, 1);
        }

        private static String field(byte[] body, int index) {
            int start = 0;
            for (int i = 0; ; i++) {
                int end = start;
                while (end < body.length && body[end] != 0) {
                    end++;
                }
                if (i == index) {
                    return new String(body, start, end - start, StandardCharsets.UTF_8);
                }
                if (end >= body.length) {
                    return "";
                }
                start = end + 1;
            }
        }

        private static byte[] intBytes(int value) {
            return new byte[] {
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
            };
        }

        // RST에 가깝게 끊기 위해 linger 0으로 닫는다.
        private static void closeBoth(Socket client, Socket server) {
            for (Socket socket : new Socket[] {client, server}) {
                try {
                    socket.setSoLinger(true, 0);
                } catch (IOException ignored) {
                    // 이미 닫혔다.
                }
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 이미 닫혔다.
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            try {
                listener.close();
            } catch (IOException ignored) {
                // 정리 중 오류는 무시한다.
            }
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 정리 중 오류는 무시한다.
                }
            }
        }
    }

    static PostgreSQLContainer<?> postgres;
    static FaultProxy proxy;
    static ConfigurableApplicationContext app;
    static String base;
    // 프록시를 거치지 않는 컨테이너 직접 연결이다. DB의 실제 상태 확인에만 쓴다.
    static JdbcTemplate direct;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        proxy = new FaultProxy(postgres.getHost(), postgres.getMappedPort(5432));
        String proxiedUrl =
                "jdbc:postgresql://127.0.0.1:"
                        + proxy.port()
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
                                "--spring.datasource.hikari.maximum-pool-size=3",
                                "--spring.datasource.hikari.connection-timeout=2000",
                                "--remittance.lock-timeout-ms=1000",
                                "--remittance.db-socket-timeout-seconds=10");
        base = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
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
        if (proxy != null) {
            proxy.close();
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
        signup.put("name", "커밋 유실 사용자");
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

    // 접수된 건의 USD 합계다. 일일 한도 집계의 근거 값이라 이중 반영 여부를 직접 확인한다.
    static BigDecimal acceptedUsd(Subject user) {
        return direct.queryForObject(
                "select coalesce(sum(usd_amount), 0) from remittances"
                        + " where user_id = ? and requested_time is not null",
                BigDecimal.class,
                user.id());
    }

    static void assertDefinedUnavailable(Reply reply) {
        String detail = "status=" + reply.status() + " body=" + reply.body();
        assertEquals(503, reply.status(), "정의된 503이어야 한다(500 UNKNOWN_ERROR 금지): " + detail);
        assertEquals("DB_UNAVAILABLE", reply.label(), detail);
        assertEquals("1", reply.retryAfter(), detail);
    }

    @Test
    // DB는 커밋했지만 응답이 유실되면 503이고, 같은 키 재시도는 같은 성공 응답을 재현하며 한 건만 반영된다.
    void lostCommitResponseIsRetryableAndAppliedOnce() throws Exception {
        Subject user = newUser("lost");
        String quoteId = quote(user, "100000");
        // 기준선: 같은 조건의 정상 접수 응답(다른 견적·키)이다.
        String baselineQuote = quote(user, "100000");
        Reply baseline = request(user, baselineQuote, "lost-baseline");
        assertEquals(200, baseline.status());
        assertEquals(1, accepted(user));
        BigDecimal usdBefore = acceptedUsd(user);

        proxy.arm(Fault.AFTER_SERVER_COMMIT);
        long begin = System.nanoTime();
        Reply first = request(user, quoteId, "lost-key");
        long firstMs = (System.nanoTime() - begin) / 1_000_000;
        System.out.println(
                "커밋 응답 유실 첫 응답: "
                        + first.status()
                        + " "
                        + first.label()
                        + " "
                        + firstMs
                        + "ms retryAfter="
                        + first.retryAfter());
        assertEquals(1, proxy.injected(), "결함이 정확히 한 번 주입되어야 한다");

        // DB에는 커밋이 남아 있다(앱만 모른다).
        assertEquals(1, rowsForKey(user, "lost-key"));
        assertEquals(2, accepted(user));
        BigDecimal usdAfterFirst = acceptedUsd(user);
        assertTrue(usdAfterFirst.compareTo(usdBefore) > 0, "커밋된 접수가 합계에 반영되어야 한다");
        assertDefinedUnavailable(first);

        // 재시도: 멱등 재생으로 200이며 아무것도 더 바꾸지 않는다.
        Reply retry = request(user, quoteId, "lost-key");
        assertEquals(200, retry.status(), retry.body().toString());
        assertEquals(baseline.body(), retry.body(), "정상 성공과 같은 응답이어야 한다");
        assertEquals(1, rowsForKey(user, "lost-key"));
        assertEquals(2, accepted(user));
        assertEquals(0, usdAfterFirst.compareTo(acceptedUsd(user)), "한도 집계가 이중 반영되면 안 된다");
        // 한 번 더 재시도해도 같은 응답이다.
        Reply again = request(user, quoteId, "lost-key");
        assertEquals(retry.body(), again.body());
        assertEquals(1, rowsForKey(user, "lost-key"));

        // 이력 조회의 오늘 건수·합계도 DB 상태와 일치한다.
        Reply history = call("GET", "/transfer/list", user.token(), null, null);
        assertEquals(200, history.status());
        assertEquals(2, history.body().get("todayTransferCount").asInt());
        assertEquals(
                0,
                acceptedUsd(user)
                        .compareTo(history.body().get("todayTransferUsdAmount").decimalValue()));
        // 무관한 동작: 다른 사용자의 접수는 영향 없이 성공한다.
        Subject other = newUser("lost-other");
        assertEquals(200, request(other, quote(other, "100000"), "other-key").status());
    }

    @Test
    // COMMIT이 서버에 닿기 전에 끊기면 롤백되어 행이 없고, 같은 키 재시도가 정확히 한 번 접수된다.
    void lostBeforeCommitRollsBackAndRetrySucceedsOnce() throws Exception {
        Subject user = newUser("rollback");
        String quoteId = quote(user, "100000");

        proxy.arm(Fault.BEFORE_SERVER_COMMIT);
        Reply first = request(user, quoteId, "rollback-key");
        System.out.println("COMMIT 도달 전 유실 첫 응답: " + first.status() + " " + first.label());
        assertEquals(1, proxy.injected(), "결함이 정확히 한 번 주입되어야 한다");
        assertDefinedUnavailable(first);

        assertEquals(0, rowsForKey(user, "rollback-key"));
        assertEquals(0, accepted(user));
        assertEquals(0, acceptedUsd(user).compareTo(BigDecimal.ZERO));

        Reply retry = request(user, quoteId, "rollback-key");
        assertEquals(200, retry.status(), retry.body().toString());
        assertEquals(1, rowsForKey(user, "rollback-key"));
        assertEquals(1, accepted(user));
    }
}
