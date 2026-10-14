package com.nelson.remittance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

// postgres 프로파일이 실제 PostgreSQL에서 기동하고 같은 API 계약으로 동작하는지 검증한다.
// Docker가 필요하며 없으면 건너뛰지 않고 실패한다. 기본 test 태스크에서는 제외되고 postgresTest로 실행한다.
// 컨텍스트가 뜬 것 자체가 Flyway V1~V2 적용과 ddl-auto: validate(엔티티-스키마 일치) 통과를 뜻한다.
@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
// 키·환율·락 타임아웃(2000ms)은 test에서, PG 전용 Flyway 위치와 init SQL은 postgres에서 온다.
// 접속 URL은 @DynamicPropertySource가 정하므로 PostgreSQL 사용 증명은 Flyway 이력(PG 전용
// 위치만 읽음)과 lock_timeout 단언이 맡는다.
@ActiveProfiles({"test", "postgres"})
class PostgresProfileTest {
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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void usesPostgresqlNotH2() {
        String version = jdbc.queryForObject("select version()", String.class);
        assertTrue(version.contains("PostgreSQL"), version);
    }

    @Test
    void appliesOnlyPostgresqlMigrations() {
        List<String> applied =
                jdbc.queryForList(
                        "select version || ':' || success from flyway_schema_history"
                                + " where version is not null order by installed_rank",
                        String.class);
        assertEquals(List.of("1:true", "2:true"), applied);
    }

    @Test
    void appliesLockTimeoutToPoolConnections() throws Exception {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rs =
                        statement.executeQuery(
                                "select setting, unit from pg_settings"
                                        + " where name = 'lock_timeout'")) {
            assertTrue(rs.next());
            assertEquals("2000", rs.getString("setting"));
            assertEquals("ms", rs.getString("unit"));
        }
    }

    @Test
    void createsUniqueConstraintsAndIndex() {
        List<String> constraints =
                jdbc.queryForList(
                        "select conname from pg_constraint where contype = 'u'"
                                + " and conname in ('uk_users_user_id',"
                                + " 'uk_remittances_user_idempotency_key')",
                        String.class);
        assertEquals(2, constraints.size(), constraints.toString());
        Integer indexes =
                jdbc.queryForObject(
                        "select count(*) from pg_indexes where tablename = 'remittances'"
                                + " and indexname = 'idx_remittances_user_requested'",
                        Integer.class);
        assertEquals(1, indexes);
    }

    @Test
    void checkConstraintRestrictsIdTypeLikeEnum() {
        String insert =
                "insert into users (user_id, password, name, id_type, id_value,"
                        + " created_at, updated_at) values (?, 'dummy-hash', 'dummy', ?,"
                        + " 'dummy-identity', now(), now())";
        assertEquals(1, jdbc.update(insert, "check-reg@example.com", "REG_NO"));
        assertEquals(1, jdbc.update(insert, "check-biz@example.com", "BUSINESS_NO"));
        var thrown =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> jdbc.update(insert, "check-bad@example.com", "X"));
        var cause = (SQLException) NestedExceptionUtils.getMostSpecificCause(thrown);
        // 23514는 CHECK 위반이며 유일·NOT NULL 위반(23505, 23502)과 구분된다.
        assertEquals("23514", cause.getSQLState());
    }

    private String body(Object... pairs) throws Exception {
        var node = json.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            node.putPOJO((String) pairs[i], pairs[i + 1]);
        }
        return json.writeValueAsString(node);
    }

    private String postJson(String path, String token, String idempotencyKey, String content)
            throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(content);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    @Test
    void apiSmokeOnPostgresql() throws Exception {
        mvc.perform(
                        post("/user/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        body(
                                                "userId", "pg-smoke@example.com",
                                                "password", "example-password",
                                                "name", "스모크 사용자",
                                                "idType", "REG_NO",
                                                "idValue", "example-identity")))
                .andExpect(status().isOk());
        String login =
                postJson(
                        "/user/login",
                        null,
                        null,
                        body("userId", "pg-smoke@example.com", "password", "example-password"));
        String token = json.readTree(login).get("token").asText();

        String quote =
                postJson(
                        "/transfer/quote",
                        token,
                        null,
                        body("amount", 400000, "targetCurrency", "USD"));
        String quoteId = json.readTree(quote).at("/quote/quoteId").asText();
        String requestBody = body("quoteId", quoteId);

        // 테스트 환율 1000 기준: 수수료 1800원을 뺀 398200원이 USD 398.2다.
        mvc.perform(
                        post("/transfer/request")
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", "pg-smoke-key-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isOk());
        // 같은 키 재요청은 중복 반영 없이 성공 응답을 재현한다.
        mvc.perform(
                        post("/transfer/request")
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", "pg-smoke-key-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isOk());
        // 키 없는 재요청은 이미 접수된 견적이라 거절된다.
        mvc.perform(
                        post("/transfer/request")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultMsg").value("ALREADY_REQUESTED"));

        mvc.perform(get("/transfer/list").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("pg-smoke@example.com"))
                .andExpect(jsonPath("$.todayTransferCount").value(1))
                .andExpect(jsonPath("$.todayTransferUsdAmount").value(398.2))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].sourceAmount").value(400000))
                .andExpect(jsonPath("$.history[0].fee").value(1800))
                .andExpect(jsonPath("$.history[0].targetCurrency").value("USD"))
                .andExpect(jsonPath("$.history[0].targetAmount").value(398.2));
    }
}
