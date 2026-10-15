package com.nelson.remittance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nelson.remittance.bootstrap.diagnostics.DatabaseConnectionFailureAnalyzer;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.testcontainers.containers.PostgreSQLContainer;

// 실제 PostgreSQL에서 접속 실패 세 종류(인증, 연결 불가, DB 없음)가 SQLState로 분류되는지 확인한다.
// Docker가 필요하며 기본 test 태스크에서는 제외되고 postgresTest로 실행한다.
@Tag("postgres")
class PostgresConnectionFailureTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        // 클래스당 컨테이너 하나를 쓰며 종료는 Testcontainers(Ryuk)가 맡는다.
        POSTGRES.start();
    }

    // postgres 프로파일 앱을 주어진 접속 정보로 기동해 실패시키고 분석 결과를 돌려준다.
    // 풀 타임아웃은 짧게 두어 실패가 오래 걸리지 않게 한다.
    private static FailureAnalysis analyzeFailure(String url, String user, String password) {
        Throwable failure =
                assertThrows(
                        Throwable.class,
                        () ->
                                new SpringApplicationBuilder(RemittanceApplication.class)
                                        .web(WebApplicationType.NONE)
                                        .profiles("test", "postgres")
                                        .run(
                                                "--spring.datasource.url=" + url,
                                                "--spring.datasource.username=" + user,
                                                "--spring.datasource.password=" + password,
                                                "--spring.datasource.hikari.connection-timeout=3000",
                                                "--spring.main.banner-mode=off"));
        FailureAnalysis analysis = new DatabaseConnectionFailureAnalyzer().analyze(failure);
        assertNotNull(analysis, "접속 실패 연쇄를 분석해야 한다: " + failure);
        return analysis;
    }

    // 안내에 비밀 값·사용자명·호스트·포트·URL이 없음을 확인한다.
    private static void assertNoLeak(FailureAnalysis analysis, String... secrets) {
        String text = analysis.getDescription() + "\n" + analysis.getAction();
        assertFalse(text.contains("jdbc:"), text);
        assertFalse(text.contains(POSTGRES.getHost()), text);
        assertFalse(text.contains(String.valueOf(POSTGRES.getFirstMappedPort())), text);
        assertFalse(text.contains(POSTGRES.getUsername()), text);
        assertFalse(text.contains(POSTGRES.getPassword()), text);
        for (String secret : secrets) {
            assertFalse(text.contains(secret), text);
        }
    }

    @Test
    // 사용자는 맞고 비밀번호만 틀리면 28P01 인증 실패 안내가 나온다.
    void wrongPasswordIsAuthenticationFailure() {
        FailureAnalysis analysis =
                analyzeFailure(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), "wrong-pw-example");
        assertTrue(analysis.getDescription().contains("28P01"), analysis.getDescription());
        assertTrue(analysis.getAction().contains("REMITTANCE_DB_PASSWORD"));
        assertTrue(analysis.getAction().contains("pg_hba.conf"));
        assertFalse(analysis.getAction().contains("trace.db"));
        assertNoLeak(analysis, "wrong-pw-example");
    }

    @Test
    // 아무도 듣지 않는 포트로 접속하면 08 계열 연결 불가 안내가 나온다.
    void closedPortIsConnectionFailure() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        FailureAnalysis analysis =
                analyzeFailure(
                        "jdbc:postgresql://localhost:" + freePort + "/remittance",
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());
        assertTrue(analysis.getDescription().contains("SQLState 08"), analysis.getDescription());
        assertTrue(analysis.getAction().contains("REMITTANCE_DB_URL"));
        assertNoLeak(analysis, String.valueOf(freePort));
    }

    @Test
    // 서버에 없는 DB 이름으로 접속하면 3D000 DB 없음 안내가 나온다.
    void missingDatabaseIsReported() {
        String url =
                "jdbc:postgresql://"
                        + POSTGRES.getHost()
                        + ":"
                        + POSTGRES.getFirstMappedPort()
                        + "/no_such_db_example";
        FailureAnalysis analysis =
                analyzeFailure(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        assertTrue(analysis.getDescription().contains("3D000"), analysis.getDescription());
        assertNoLeak(analysis, "no_such_db_example");
    }
}
