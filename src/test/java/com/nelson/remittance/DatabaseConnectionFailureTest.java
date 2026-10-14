package com.nelson.remittance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nelson.remittance.bootstrap.DatabaseConnectionFailureAnalyzer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.internal.exception.FlywaySqlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.support.SpringFactoriesLoader;

// 실제 H2에서 비밀번호가 틀린 기동을 재현해 Flyway 접속 실패가 인증 실패 안내로 분류되는지 확인한다.
// 메모리 DB는 먼저 연 연결이 살아 있는 동안 같은 URL로 유지되므로 파일이나 컨테이너가 필요 없다.
@ExtendWith(OutputCaptureExtension.class)
class DatabaseConnectionFailureTest {
    private static final String RIGHT = "right-pw";
    private static final String WRONG = "wrong-pw";

    // 잘못된 비밀번호로 앱을 기동해 실패시키고 던져진 예외를 돌려준다.
    private static Throwable startWithWrongPassword(String url) {
        return assertThrows(
                Throwable.class,
                () ->
                        new SpringApplicationBuilder(RemittanceApplication.class)
                                .web(WebApplicationType.NONE)
                                .profiles("test")
                                .run(
                                        "--spring.datasource.url=" + url,
                                        "--spring.datasource.password=" + WRONG,
                                        "--spring.main.banner-mode=off"));
    }

    @Test
    // Flyway가 먼저 실패하는 인증 오류가 SQLState만으로 분류되고 안내에 비밀 값이 없다.
    void classifiesWrongPasswordFromFlywayFailure(CapturedOutput output) throws SQLException {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection keepAlive = DriverManager.getConnection(url, "sa", RIGHT)) {
            assertNotNull(keepAlive);
            Throwable failure = startWithWrongPassword(url);

            FailureAnalysis analysis = new DatabaseConnectionFailureAnalyzer().analyze(failure);
            assertNotNull(analysis, "Flyway 접속 실패 연쇄를 분석해야 한다: " + failure);
            String text = analysis.getDescription() + "\n" + analysis.getAction();
            assertTrue(text.contains("28000"), text);
            assertTrue(analysis.getAction().contains("REMITTANCE_DB_PASSWORD"), text);
            assertNoSecrets(text);

            // H2는 고정 비밀번호 안내가 있고 PostgreSQL 전용 안내는 없다.
            assertTrue(analysis.getAction().contains("remittance.trace.db"), text);
            assertFalse(analysis.getAction().contains("pg_hba.conf"), text);

            // 실제 기동 실패 보고(콘솔 블록)에도 같은 안내가 나간다. 블록 앞의 WARN 줄은 라이브러리 로그라
            // 제외하고 'APPLICATION FAILED TO START'부터 다음 로그 줄 직전까지만 비노출을 단언한다.
            String console = output.getAll();
            int start = console.indexOf("APPLICATION FAILED TO START");
            assertTrue(start >= 0, "기동 실패 안내 블록이 출력되어야 한다");
            int end = console.indexOf("\n20", start);
            String block = end < 0 ? console.substring(start) : console.substring(start, end);
            assertTrue(block.contains("REMITTANCE_DB_PASSWORD"), block);
            assertNoSecrets(block);
        }
    }

    @Test
    // 접속 실패가 아닌 Flyway 오류와 무관한 예외는 가로채지 않는다.
    void ignoresNonConnectionFailures() {
        var analyzer = new DatabaseConnectionFailureAnalyzer();
        assertNull(analyzer.analyze(new IllegalStateException("something else")));
        assertNull(
                analyzer.analyze(
                        new IllegalStateException(
                                "wrapped", new FlywayException("Migration V1 failed"))));
    }

    @Test
    // spring.factories에 분석기가 등록되어 있어야 부팅 시 동작한다.
    void registeredInSpringFactories() {
        assertTrue(
                SpringFactoriesLoader.loadFactoryNames(FailureAnalyzer.class, null)
                        .contains(DatabaseConnectionFailureAnalyzer.class.getName()));
    }

    private static FailureAnalysis analyze(Throwable... chainTop) {
        return new DatabaseConnectionFailureAnalyzer().analyze(chainTop[0]);
    }

    // 접속 실패 접두사를 가진 Flyway 예외를 합성한다.
    private static Exception flywayConnect(SQLException cause) {
        return new IllegalStateException(
                "context failed",
                new FlywaySqlException("Unable to obtain connection from database: x", cause));
    }

    private static String text(FailureAnalysis a) {
        return a.getDescription() + "\n" + a.getAction();
    }

    @Test
    // 인증(28)과 연결 불가(08)가 함께 있으면 인증 안내가 우선한다.
    void authenticationWinsOverUnreachable() {
        SQLException first = new SQLException("a", "08001");
        first.setNextException(new SQLException("b", "28P01"));
        FailureAnalysis a = analyze(flywayConnect(first));
        assertNotNull(a);
        assertTrue(a.getDescription().contains("28P01"), text(a));
    }

    @Test
    // DB 없음(3D000)이 연결 불가(08)보다 우선한다.
    void missingDatabaseWinsOverUnreachable() {
        SQLException first = new SQLException("a", "08006");
        first.setNextException(new SQLException("b", "3D000"));
        FailureAnalysis a = analyze(flywayConnect(first));
        assertNotNull(a);
        assertTrue(a.getDescription().contains("3D000"), text(a));
    }

    @Test
    // SQLState가 없거나 형식이 틀리면 일반 안내를 하고 그 문자열은 출력하지 않는다.
    void unknownOrMalformedSqlStateGivesGenericNotice() {
        for (String state : new String[] {null, "28x00", "2800", "28000\nforged", "08001 user=a"}) {
            FailureAnalysis a = analyze(flywayConnect(new SQLException("m", state)));
            assertNotNull(a);
            assertTrue(a.getDescription().contains("분류할 수 없습니다"), text(a));
            if (state != null) {
                assertFalse(text(a).contains(state), text(a));
            }
            assertFalse(text(a).contains("SQLState "), text(a));
        }
    }

    @Test
    // nextException 체인의 SQLState도 분류하고 순환 체인에서도 끝난다.
    void classifiesNextExceptionAndSurvivesCycles() {
        SQLException a = new SQLException("a", "XX000");
        SQLException b = new SQLException("b", "28000");
        a.setNextException(b);
        assertTrue(analyze(flywayConnect(a)).getDescription().contains("28000"));

        // 원인(cause)이 서로를 가리키는 순환이어도 깊이 제한으로 끝난다.
        // nextException 순환은 Flyway 자체의 getMessage가 먼저 무한 순회하므로 합성하지 않는다.
        SQLException x = new SQLException("x", "XX000");
        SQLException y = new SQLException("y", "XX000");
        x.initCause(y);
        y.initCause(x);
        FailureAnalysis cyc =
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> analyze(flywayConnect(x)));
        assertNotNull(cyc);
        assertTrue(cyc.getDescription().contains("분류할 수 없습니다"), text(cyc));
    }

    @Test
    // 클래스는 FlywaySqlException이어도 접속 실패 접두사가 아니면 가로채지 않는다.
    void ignoresFlywaySqlExceptionWithOtherMessage() {
        Exception other =
                new IllegalStateException(
                        new FlywaySqlException(
                                "Migration of schema failed", new SQLException("m", "28000")));
        assertNull(analyze(other));
    }

    @Test
    // PostgreSQL 예외는 pg_hba 안내만, 종류를 모르면 두 안내를 모두 담는다.
    void authenticationActionDependsOnDriverFamily() throws Exception {
        Class<?> state = Class.forName("org.postgresql.util.PSQLState");
        Object invalidPassword = state.getField("INVALID_PASSWORD").get(null);
        SQLException pg =
                (SQLException)
                        Class.forName("org.postgresql.util.PSQLException")
                                .getConstructor(String.class, state)
                                .newInstance("m", invalidPassword);
        FailureAnalysis a = analyze(flywayConnect(pg));
        assertTrue(a.getDescription().contains("28P01"), text(a));
        assertTrue(a.getAction().contains("pg_hba.conf"), text(a));
        assertFalse(a.getAction().contains("trace.db"), text(a));

        FailureAnalysis unknown = analyze(flywayConnect(new SQLException("m", "28000")));
        assertTrue(unknown.getAction().contains("pg_hba.conf"), text(unknown));
        assertTrue(unknown.getAction().contains("trace.db"), text(unknown));
    }

    static void assertNoSecrets(String text) {
        for (String secret : new String[] {WRONG, RIGHT, "jdbc:", "mem:"}) {
            assertFalse(text.contains(secret), "노출되면 안 되는 값: " + secret);
        }
    }
}
