package com.nelson.remittance.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

// 견적 저장(INSERT)이 remittances.user_id 외래키 때문에 사용자 행에 공유 잠금을 요청한다.
// 다른 연결이 그 행을 FOR UPDATE로 쥐면 견적 발급도 lock_timeout(55P03)에 걸리므로,
// 이 경로도 접수와 같이 재시도 가능한 503 LOCK_TIMEOUT으로 응답하는지 실제 PostgreSQL에서 검증한다.
// Docker가 필요하며 postgresTest로만 실행된다.
@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "postgres"})
class PostgresQuoteLockTimeoutTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
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

    MvcResult send(String path, String token, Object body) throws Exception {
        var builder = post(path).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(builder.content(json.writeValueAsString(body))).andReturn();
    }

    MvcResult quote(String token) throws Exception {
        return send(
                "/transfer/quote",
                token,
                Map.of("amount", new BigDecimal("100000"), "targetCurrency", "USD"));
    }

    @Test
    void quoteIssueTimesOutAs503WhenUserRowIsLockedAndRecoversAfterRelease() throws Exception {
        String email = "pg-quote-lock-" + System.nanoTime() + "@example.com";
        send(
                "/user/signup",
                null,
                Map.of(
                        "userId",
                        email,
                        "password",
                        PASSWORD,
                        "name",
                        "견적 잠금 사용자",
                        "idType",
                        "REG_NO",
                        "idValue",
                        "example-identity"));
        MvcResult login = send("/user/login", null, Map.of("userId", email, "password", PASSWORD));
        assertEquals(200, login.getResponse().getStatus());
        String token =
                json.readTree(login.getResponse().getContentAsString()).get("token").asText();
        long id = jdbc.queryForObject("select id from users where user_id = ?", Long.class, email);

        Connection lock =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        lock.setAutoCommit(false);
        try {
            try (var statement =
                    lock.prepareStatement("select id from users where id = ? for update")) {
                statement.setLong(1, id);
                assertTrue(statement.executeQuery().next());
            }
            long started = System.nanoTime();
            MvcResult timedOut = quote(token);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(
                    503,
                    timedOut.getResponse().getStatus(),
                    timedOut.getResponse().getContentAsString());
            assertEquals(
                    "LOCK_TIMEOUT",
                    json.readTree(timedOut.getResponse().getContentAsString())
                            .get("resultMsg")
                            .asText());
            assertEquals("1", timedOut.getResponse().getHeader("Retry-After"));
            // 설정된 lock_timeout(application-test.yaml의 2000ms)을 기다린 뒤 끊겨야 하며 무한 대기해서는 안 된다.
            // 상한은 느린 환경에서도 흔들리지 않게 넉넉히 둔 '무한 대기 아님' 수준이다.
            assertTrue(elapsedMs >= 2000 && elapsedMs < 10000, "경과 ms: " + elapsedMs);
        } finally {
            lock.rollback();
            lock.close();
        }
        // 타임아웃된 시도는 견적 행을 남기지 않는다.
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from remittances where user_id = ?", Integer.class, id));
        // 잠금이 풀린 뒤 같은 요청은 성공하고 견적이 정확히 한 건만 생긴다.
        assertEquals(200, quote(token).getResponse().getStatus());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from remittances where user_id = ?", Integer.class, id));
    }
}
