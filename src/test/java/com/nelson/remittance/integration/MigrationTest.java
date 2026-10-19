package com.nelson.remittance.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

// Spring 컨텍스트 없이 Flyway와 JDBC만으로 마이그레이션 스크립트의 업그레이드 경로를 고정한다.
// 테스트마다 고유한 메모리 DB를 쓰므로 서로, 그리고 사용자의 파일 DB와 격리된다.
class MigrationTest {

    private static final String UNIQUE_NAME = "UK_REMITTANCES_USER_IDEMPOTENCY_KEY";

    private static String newUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private static Connection open(String url) throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    private static Flyway flyway(String url, String target, boolean baseline) {
        var config =
                Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        if (baseline) {
            // 운영 설정(application.yaml)과 같은 기준선 옵션이다.
            config.baselineOnMigrate(true).baselineVersion("0");
        }
        return config.load();
    }

    private static String currentVersion(Flyway flyway) {
        return flyway.info().current().getVersion().getVersion();
    }

    private static void exec(String url, String sql) throws SQLException {
        try (Connection c = open(url);
                Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long count(String url, String sql) throws SQLException {
        try (Connection c = open(url);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void insertUser(String url) throws SQLException {
        exec(
                url,
                "INSERT INTO users (user_id, password, name, id_type, id_value, created_at, updated_at) "
                        + "VALUES ('u1', 'pw', 'name', 'REG_NO', 'example-identity', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    // idempotency_key 컬럼이 없는 구 스키마에서도 쓸 수 있도록 키를 따로 받지 않는 접수 행이다.
    private static void insertRequestedRemittance(String url) throws SQLException {
        exec(
                url,
                "INSERT INTO remittances (user_id, source_amount, target_amount, target_currency, "
                        + "exchange_rate, fixed_fee, rated_fee, fee_ratio, usd_amount, usd_exchange_rate, "
                        + "expire_time, requested_time, created_at, updated_at) "
                        + "VALUES ((SELECT id FROM users WHERE user_id = 'u1'), 1000, 700, 'JPY', "
                        + "0.7, 1, 1, 0.001, 0.75, 1.3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    private static void insertKeyed(String url, String key) throws SQLException {
        String value = key == null ? "NULL" : "'" + key + "'";
        exec(
                url,
                "INSERT INTO remittances (user_id, source_amount, target_amount, target_currency, "
                        + "exchange_rate, fixed_fee, rated_fee, fee_ratio, usd_amount, usd_exchange_rate, "
                        + "expire_time, requested_time, created_at, updated_at, idempotency_key) "
                        + "VALUES ((SELECT id FROM users WHERE user_id = 'u1'), 1000, 700, 'JPY', "
                        + "0.7, 1, 1, 0.001, 0.75, 1.3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                        + value
                        + ")");
    }

    private static long columnCount(String url, String table, String column) throws SQLException {
        return count(
                url,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '"
                        + table
                        + "' AND COLUMN_NAME = '"
                        + column
                        + "'");
    }

    private static long uniqueConstraintCount(String url) throws SQLException {
        return count(
                url,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'REMITTANCES' AND CONSTRAINT_TYPE = 'UNIQUE' "
                        + "AND CONSTRAINT_NAME = '"
                        + UNIQUE_NAME
                        + "'");
    }

    @Test
    void emptyDatabaseMigratesToLatestSchema() throws SQLException {
        String url = newUrl();
        Flyway flyway = flyway(url, null, false);

        flyway.migrate();

        assertEquals("3", currentVersion(flyway));
        assertEquals(1, columnCount(url, "USERS", "USER_ID"));
        assertEquals(1, columnCount(url, "REMITTANCES", "IDEMPOTENCY_KEY"));
        assertEquals(1, uniqueConstraintCount(url));
    }

    @Test
    void upgradeFromV1KeepsRowsAndEnforcesIdempotencyUniqueness() throws SQLException {
        String url = newUrl();
        flyway(url, "1", false).migrate();
        insertUser(url);
        insertRequestedRemittance(url);
        assertEquals(0, columnCount(url, "REMITTANCES", "IDEMPOTENCY_KEY"));

        Flyway latest = flyway(url, null, false);
        latest.migrate();

        assertEquals("3", currentVersion(latest));
        assertEquals(1, count(url, "SELECT COUNT(*) FROM users"));
        assertEquals(
                1, count(url, "SELECT COUNT(*) FROM remittances WHERE requested_time IS NOT NULL"));
        try (Connection c = open(url);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT idempotency_key FROM remittances")) {
            assertTrue(rs.next());
            assertNull(rs.getString(1));
        }

        insertKeyed(url, "key-1");
        SQLException duplicate = assertThrows(SQLException.class, () -> insertKeyed(url, "key-1"));
        assertEquals("23505", duplicate.getSQLState());

        // 키가 없는 행은 NULL끼리 충돌하지 않아 여러 건 접수할 수 있어야 한다.
        insertKeyed(url, null);
        insertKeyed(url, null);
        assertEquals(
                1, count(url, "SELECT COUNT(*) FROM remittances WHERE idempotency_key = 'key-1'"));
        assertEquals(
                3, count(url, "SELECT COUNT(*) FROM remittances WHERE idempotency_key IS NULL"));
    }

    @Test
    void latestSchemaWithoutHistoryBaselinesWithoutDuplicatingConstraint() throws SQLException {
        String url = newUrl();
        flyway(url, null, false).migrate();
        insertUser(url);
        insertKeyed(url, "key-1");
        // ddl-auto로 이미 최신 스키마가 만들어진 기존 DB처럼 이력만 없는 상태를 만든다.
        exec(url, "DROP TABLE \"flyway_schema_history\"");

        Flyway rerun = flyway(url, null, true);
        rerun.migrate();

        assertEquals("3", currentVersion(rerun));
        assertEquals(1, count(url, "SELECT COUNT(*) FROM users"));
        assertEquals(
                1, count(url, "SELECT COUNT(*) FROM remittances WHERE idempotency_key = 'key-1'"));
        assertEquals(1, uniqueConstraintCount(url));
    }

    @Test
    void oldSchemaWithoutHistoryBaselinesAndUpgrades() throws SQLException {
        String url = newUrl();
        flyway(url, "1", false).migrate();
        insertUser(url);
        insertRequestedRemittance(url);
        exec(url, "DROP TABLE \"flyway_schema_history\"");

        Flyway rerun = flyway(url, null, true);
        rerun.migrate();

        assertEquals("3", currentVersion(rerun));
        assertEquals(1, count(url, "SELECT COUNT(*) FROM users"));
        assertEquals(1, count(url, "SELECT COUNT(*) FROM remittances"));
        assertEquals(1, columnCount(url, "REMITTANCES", "IDEMPOTENCY_KEY"));
        assertEquals(1, uniqueConstraintCount(url));
    }

    private static void insertUserId(String url, String userId) throws SQLException {
        exec(
                url,
                "INSERT INTO users (user_id, password, name, id_type, id_value, created_at, updated_at) "
                        + "VALUES ('"
                        + userId
                        + "', 'pw', 'name', 'REG_NO', 'example-identity', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    // V2 상태의 대소문자 혼재 계정이 V3에서 소문자로 바뀌고 다른 행·연결(접수 건)은 유지되는지 확인한다.
    @Test
    void v3LowercasesExistingUserIdsAndKeepsRelations() throws SQLException {
        String url = newUrl();
        flyway(url, "2", false).migrate();
        insertUser(url);
        insertUserId(url, "Alice@Example.COM");
        insertRequestedRemittance(url);

        flyway(url, null, false).migrate();

        assertEquals(
                1, count(url, "SELECT COUNT(*) FROM users WHERE user_id = 'alice@example.com'"));
        assertEquals(
                0, count(url, "SELECT COUNT(*) FROM users WHERE user_id = 'Alice@Example.COM'"));
        assertEquals(1, count(url, "SELECT COUNT(*) FROM users WHERE user_id = 'u1'"));
        assertEquals(1, count(url, "SELECT COUNT(*) FROM remittances"));
    }

    // 정규화 후 중복이 되는 계정이 있으면 조용히 합치지 않고 마이그레이션이 실패하며 데이터는 그대로다.
    @Test
    void v3FailsInsteadOfMergingCaseOnlyDuplicates() throws SQLException {
        String url = newUrl();
        flyway(url, "2", false).migrate();
        insertUserId(url, "Dup@Example.com");
        insertUserId(url, "dup@example.com");

        Flyway latest = flyway(url, null, false);
        assertThrows(org.flywaydb.core.api.FlywayException.class, latest::migrate);

        assertEquals(2, count(url, "SELECT COUNT(*) FROM users"));
        assertEquals(1, count(url, "SELECT COUNT(*) FROM users WHERE user_id = 'Dup@Example.com'"));
    }
}
