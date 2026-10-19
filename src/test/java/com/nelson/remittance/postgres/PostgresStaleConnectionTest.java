package com.nelson.remittance.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

// DB가 풀의 연결을 끊은 직후 첫 요청이 500(UNKNOWN_ERROR)이 아니라 재시도 가능한 503(DB_UNAVAILABLE)이 되는지 검증한다.
// Hikari는 직전 500ms 안에 쓴 연결의 생존 확인을 건너뛰므로(aliveBypassWindow) 끊긴 연결이 그대로 요청에 전달될 수 있다.
// Docker가 필요하며 없으면 건너뛰지 않고 실패한다. postgresTest로만 실행된다.
@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "postgres"})
class PostgresStaleConnectionTest {
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

    static final String PASSWORD = "example-password";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    record Subject(String token, long id) {}

    Subject newUser() throws Exception {
        String email = "stale-" + System.nanoTime() + "@example.com";
        mvc.perform(
                post("/user/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                json.writeValueAsString(
                                        Map.of(
                                                "userId", email,
                                                "password", PASSWORD,
                                                "name", "끊김 사용자",
                                                "idType", "REG_NO",
                                                "idValue", "example-identity"))));
        MvcResult login =
                mvc.perform(
                                post("/user/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "userId",
                                                                email,
                                                                "password",
                                                                PASSWORD))))
                        .andReturn();
        assertEquals(200, login.getResponse().getStatus());
        String token =
                json.readTree(login.getResponse().getContentAsString()).get("token").asText();
        long id = jdbc.queryForObject("select id from users where user_id = ?", Long.class, email);
        return new Subject(token, id);
    }

    MvcResult history(Subject user) throws Exception {
        return mvc.perform(get("/transfer/list").header("Authorization", "Bearer " + user.token()))
                .andReturn();
    }

    MvcResult request(Subject user, String quoteId, String key) throws Exception {
        return mvc.perform(
                        post("/transfer/request")
                                .header("Authorization", "Bearer " + user.token())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("quoteId", quoteId))))
                .andReturn();
    }

    // 앱이 쓰던 모든 연결을 DB가 끊고, 백엔드가 사라질 때까지만 기다린다.
    // 오래 기다리면 풀의 생존 확인 창(500ms)을 넘겨 끊긴 연결이 미리 걸러져 이 시나리오가 사라진다.
    void terminateAllAppConnections() throws SQLException, InterruptedException {
        try (Connection admin =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var kill =
                        admin.createStatement()
                                .executeQuery(
                                        "select count(pg_terminate_backend(pid)) from pg_stat_activity"
                                                + " where datname = current_database()"
                                                + " and pid <> pg_backend_pid()")) {
            assertTrue(kill.next());
            assertTrue(kill.getInt(1) > 0, "끊은 연결이 있어야 한다");
            for (int i = 0; i < 40; i++) {
                var rs =
                        admin.createStatement()
                                .executeQuery(
                                        "select count(*) from pg_stat_activity"
                                                + " where datname = current_database()"
                                                + " and pid <> pg_backend_pid()");
                rs.next();
                if (rs.getInt(1) == 0) {
                    return;
                }
                Thread.sleep(5);
            }
            // 조용히 반환하면 시나리오 성립 여부를 알 수 없으므로 명시적으로 실패시킨다.
            throw new AssertionError("백엔드가 제때 종료되지 않았다");
        }
    }

    @Test
    // 끊긴 연결로 인한 첫 실패는 500이 아니라 Retry-After가 붙은 503 DB_UNAVAILABLE이고,
    // 같은 Idempotency-Key로 재시도하면 접수가 정확히 한 번만 반영된다.
    void firstRequestAfterConnectionLossIsRetryable503() throws Exception {
        Subject user = newUser();
        MvcResult quote =
                mvc.perform(
                                post("/transfer/quote")
                                        .header("Authorization", "Bearer " + user.token())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"amount\":100000,\"targetCurrency\":\"USD\"}"))
                        .andReturn();
        String quoteId =
                json.readTree(quote.getResponse().getContentAsString())
                        .at("/quote/quoteId")
                        .asText();
        // 풀이 여러 연결을 열어 둔 상태에서 끊어, 다음 요청이 끊긴 연결을 집게 한다.
        for (int i = 0; i < 5; i++) {
            assertEquals(200, history(user).getResponse().getStatus());
        }

        terminateAllAppConnections();

        List<Integer> statuses = new ArrayList<>();
        MvcResult last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            last = request(user, quoteId, "stale-key");
            statuses.add(last.getResponse().getStatus());
            if (last.getResponse().getStatus() == 200) {
                break;
            }
            // 실패한 시도는 500이 아니라 재시도 안내가 있는 503이어야 한다.
            assertEquals(503, last.getResponse().getStatus(), statuses.toString());
            JsonNode body = json.readTree(last.getResponse().getContentAsString());
            assertEquals("DB_UNAVAILABLE", body.get("resultMsg").asText());
            assertEquals("1", last.getResponse().getHeader("Retry-After"));
            Thread.sleep(100);
        }
        assertEquals(200, last.getResponse().getStatus(), "10회 안에 복구되어야 한다: " + statuses);
        assertNotEquals(List.of(200), statuses, "끊김 직후 한 번은 실패했어야 시나리오가 성립한다");
        // 실패한 시도는 상태를 남기지 않았고, 같은 키의 재시도는 효과 1회로 재현된다.
        assertEquals(200, request(user, quoteId, "stale-key").getResponse().getStatus());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from remittances where user_id = ?"
                                + " and requested_time is not null",
                        Integer.class,
                        user.id()));
    }
}
