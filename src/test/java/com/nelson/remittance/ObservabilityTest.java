package com.nelson.remittance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.nelson.remittance.domain.exception.BusinessException;
import com.nelson.remittance.domain.exception.ErrorCode;
import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.HibernateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 요청 ID 헤더·MDC 정리, 개인정보 없는 오류 로그, 업무 오류 로그 수준, 오류 설명기, DB 접속 실패 분석기를 고정한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ObservabilityTest.Endpoints.class)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTest {
    static final String UUID_FORMAT =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    static final String SECRET_MESSAGE =
            "leak owner@example.com Bearer eyJhbGciOi.secret.token password=hunter2";

    @Autowired MockMvc mvc;

    @TestConfiguration
    static class Endpoints {
        @Bean
        ObservabilityEndpoint observabilityEndpoint() {
            return new ObservabilityEndpoint();
        }
    }

    @RestController
    static class ObservabilityEndpoint {
        @GetMapping("/_obs/failure")
        Object fail() {
            throw new IllegalStateException(SECRET_MESSAGE);
        }

        @GetMapping("/_obs/lock")
        Object lock() {
            throw new BusinessException(ErrorCode.LOCK_TIMEOUT);
        }

        @GetMapping("/_obs/business")
        Object business() {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        @GetMapping("/_obs/ok")
        String ok() {
            return "ok";
        }
    }

    // 정상·404·401 응답 모두 UUID 형식의 요청 ID 헤더를 가진다. 보안 필터보다 앞서 실행되기 때문이다.
    @Test
    void everyResponseCarriesGeneratedRequestId() throws Exception {
        for (String path : new String[] {"/_obs/ok", "/no-such-path", "/transfer/quote"}) {
            MvcResult result = mvc.perform(get(path)).andReturn();
            String id = result.getResponse().getHeader("X-Request-Id");
            assertNotNull(id, path);
            assertTrue(id.matches(UUID_FORMAT), path + " " + id);
        }
        MvcResult unauthorized = mvc.perform(post("/transfer/quote")).andReturn();
        assertEquals(401, unauthorized.getResponse().getStatus());
        assertNotNull(unauthorized.getResponse().getHeader("X-Request-Id"));
    }

    // 안전한 클라이언트 값은 그대로 채택하고, 공백·길이 초과·특수문자 값은 새 UUID로 바꾼다.
    @Test
    void acceptsOnlyWellFormedClientRequestId() throws Exception {
        String good = "client-req_1.2";
        assertEquals(good, idFor(good));
        assertEquals("a".repeat(64), idFor("a".repeat(64)));
        for (String bad :
                new String[] {" ", "has space", "a".repeat(65), "x\ty", "<script>", "a/b", "한글"}) {
            String id = idFor(bad);
            assertNotEquals(bad, id);
            assertTrue(id.matches(UUID_FORMAT), id);
        }
    }

    String idFor(String requested) throws Exception {
        return mvc.perform(get("/_obs/ok").header("X-Request-Id", requested))
                .andReturn()
                .getResponse()
                .getHeader("X-Request-Id");
    }

    // 요청이 끝난 뒤 MDC에 requestId가 남지 않는다(정상·오류 모두).
    @Test
    void clearsMdcAfterRequest() throws Exception {
        mvc.perform(get("/_obs/ok")).andReturn();
        assertNull(MDC.get("requestId"));
        mvc.perform(get("/_obs/failure")).andReturn();
        assertNull(MDC.get("requestId"));
    }

    // 500 로그에는 요청 ID·예외 클래스·메서드·경로만 있고 메시지·쿼리·본문·Authorization 값은 없으며 응답 계약은 그대로다.
    @Test
    void unexpectedErrorLogIsSafeAndResponseContractUnchanged(CapturedOutput output)
            throws Exception {
        String id = "obs-" + UUID.randomUUID();
        MvcResult result =
                mvc.perform(
                                get("/_obs/failure?email=query-secret@example.com")
                                        .header("X-Request-Id", id)
                                        .header("Authorization", "Bearer header-secret-token"))
                        .andExpect(jsonPath("$.resultCode").value(500))
                        .andExpect(jsonPath("$.resultMsg").value("UNKNOWN_ERROR"))
                        .andReturn();
        assertEquals(500, result.getResponse().getStatus());
        String logs = output.getAll();
        assertTrue(logs.contains("[" + id + "]"), "요청 ID");
        assertTrue(logs.contains("java.lang.IllegalStateException"));
        assertTrue(logs.contains("method=GET path=/_obs/failure"));
        assertFalse(logs.contains("owner@example.com"));
        assertFalse(logs.contains("eyJhbGciOi"));
        assertFalse(logs.contains("hunter2"));
        assertFalse(logs.contains("leak "));
        assertFalse(logs.contains("query-secret"));
        assertFalse(logs.contains("header-secret-token"));
    }

    // 요청 본문 값은 로그에 나오지 않는다.
    @Test
    void requestBodyIsNotLogged(CapturedOutput output) throws Exception {
        mvc.perform(
                        post("/_obs/failure")
                                .contentType("application/json")
                                .content("{\"secret\":\"body-secret-value\"}"))
                .andReturn();
        assertFalse(output.getAll().contains("body-secret-value"));
    }

    // LOCK_TIMEOUT은 WARN으로 남고 그 밖의 업무 오류는 기본 로그 수준에서 출력되지 않는다.
    @Test
    void businessErrorLogLevels(CapturedOutput output) throws Exception {
        mvc.perform(get("/_obs/business").header("X-Request-Id", "biz-debug-id")).andReturn();
        assertFalse(output.getAll().contains("biz-debug-id"));
        mvc.perform(get("/_obs/lock").header("X-Request-Id", "lock-warn-id"))
                .andExpect(jsonPath("$.resultMsg").value("LOCK_TIMEOUT"));
        String logs = output.getAll();
        assertTrue(logs.contains("WARN [lock-warn-id]"), logs);
        assertTrue(logs.contains("code=LOCK_TIMEOUT"));
    }

    // SQLException은 SQLState·벤더 코드·클래스만 설명하고 메시지는 포함하지 않는다.
    @Test
    void errorDescriptionsExcludeMessages() {
        SQLException sql =
                new SQLException("user owner@example.com wrong password", "28000", 28000);
        RuntimeException wrapped = new RuntimeException("outer private message", sql);
        String text = describe(wrapped);
        assertTrue(text.contains("java.lang.RuntimeException"));
        assertTrue(text.contains("java.sql.SQLException(sqlState=28000, vendorCode=28000)"));
        assertTrue(
                text.contains(
                        "com.nelson.remittance.ObservabilityTest.errorDescriptionsExcludeMessages"));
        assertFalse(text.contains("owner@example.com"));
        assertFalse(text.contains("private message"));
    }

    // ErrorDescriptions는 웹 어댑터 패키지 전용이라 리플렉션으로 호출한다.
    static String describe(Throwable error) {
        try {
            var type = Class.forName("com.nelson.remittance.adapter.web.ErrorDescriptions");
            var method = type.getDeclaredMethod("describe", Throwable.class);
            method.setAccessible(true);
            return (String) method.invoke(null, error);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // Dialect 결정 실패 연쇄는 안내를 반환하고 무관한 예외는 null이며 안내에 비밀 값이 없다.
    @Test
    void databaseConnectionAnalyzerDetectsDialectFailureOnly() {
        var analyzer = new com.nelson.remittance.bootstrap.DatabaseConnectionFailureAnalyzer();
        Exception failure =
                new IllegalStateException(
                        "context failed",
                        new HibernateException(
                                "Unable to determine Dialect without JDBC metadata (please set"
                                        + " 'jakarta.persistence.jdbc.url')"));
        FailureAnalysis analysis = analyzer.analyze(failure);
        assertNotNull(analysis);
        String text = analysis.getDescription() + analysis.getAction();
        assertTrue(analysis.getAction().contains("REMITTANCE_DB_PASSWORD"));
        assertTrue(analysis.getAction().contains("remittance.trace.db"));
        assertTrue(analysis.getAction().contains("SPRING_PROFILES_ACTIVE=local"));
        assertFalse(text.contains("jdbc:"));
        assertFalse(text.toLowerCase().contains("password="));
        assertNull(analyzer.analyze(new IllegalStateException("something else")));
        assertNull(analyzer.analyze(new HibernateException("other hibernate failure")));
    }

    // 체인에서 예외가 나도 예외는 전파되고, 체인 안에서는 MDC에 요청 ID가 있으며, 끝나면 MDC가 비고 응답 헤더는 남는다.
    @Test
    void filterClearsMdcAndPropagatesWhenChainThrows() {
        var filter = new com.nelson.remittance.adapter.web.RequestIdFilter();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        String[] inChain = new String[1];
        jakarta.servlet.FilterChain chain =
                (req, res) -> {
                    inChain[0] = MDC.get("requestId");
                    throw new IllegalStateException("chain failure");
                };
        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, chain));
        String header = response.getHeader("X-Request-Id");
        assertNotNull(header);
        assertEquals(header, inChain[0]);
        assertNull(MDC.get("requestId"));
    }
}
