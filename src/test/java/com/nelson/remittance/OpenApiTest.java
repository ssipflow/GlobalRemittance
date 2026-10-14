package com.nelson.remittance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// API 명세가 실제 엔드포인트·보안 요구·오류 계약과 일치하고, 끄면 기존 404 계약으로 돌아가는지 고정한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Value("${remittance.aes-key}")
    String aesKey;

    @Value("${remittance.jwt-secret}")
    String jwtSecret;

    private String docs() throws Exception {
        return mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private JsonNode operation(JsonNode root, String path, String method) {
        JsonNode node = root.path("paths").path(path).path(method);
        assertFalse(node.isMissingNode(), method + " " + path + " 오퍼레이션이 명세에 없다");
        assertFalse(node.path("summary").asText().isBlank(), path + " 요약이 비어 있다");
        return node;
    }

    @Test
    void specListsEveryOperationWithBearerSecurityOnlyOnTransfer() throws Exception {
        JsonNode root = json.readTree(docs());
        assertEquals(5, root.path("paths").size(), root.path("paths").toString());

        for (String path : new String[] {"/user/signup", "/user/login"}) {
            assertTrue(operation(root, path, "post").path("security").isMissingNode(), path);
        }
        JsonNode quote = operation(root, "/transfer/quote", "post");
        JsonNode request = operation(root, "/transfer/request", "post");
        JsonNode list = operation(root, "/transfer/list", "get");
        for (JsonNode op : new JsonNode[] {quote, request, list}) {
            assertTrue(op.path("security").get(0).has("bearerAuth"));
            assertTrue(op.path("responses").has("401"));
        }

        JsonNode scheme = root.path("components").path("securitySchemes").path("bearerAuth");
        assertEquals("http", scheme.path("type").asText());
        assertEquals("bearer", scheme.path("scheme").asText());
        assertEquals("JWT", scheme.path("bearerFormat").asText());
    }

    @Test
    void specDocumentsParametersAndErrorResponses() throws Exception {
        JsonNode root = json.readTree(docs());

        JsonNode header = null;
        for (JsonNode parameter : operation(root, "/transfer/request", "post").path("parameters")) {
            if ("Idempotency-Key".equals(parameter.path("name").asText())) {
                header = parameter;
            }
        }
        assertFalse(header == null, "Idempotency-Key 헤더가 명세에 없다");
        assertEquals("header", header.path("in").asText());
        assertFalse(header.path("required").asBoolean());

        JsonNode listParameters = operation(root, "/transfer/list", "get").path("parameters");
        int found = 0;
        for (JsonNode parameter : listParameters) {
            String name = parameter.path("name").asText();
            if (name.equals("size") || name.equals("cursor")) {
                found++;
                assertEquals("query", parameter.path("in").asText());
                assertFalse(parameter.path("required").asBoolean());
            }
            // 인증 주체는 요청 파라미터가 아니다.
            assertFalse(name.equals("actor"));
        }
        assertEquals(2, found);

        JsonNode unprocessable =
                operation(root, "/transfer/request", "post").path("responses").path("422");
        assertFalse(unprocessable.isMissingNode());
        assertTrue(
                unprocessable
                        .path("content")
                        .path("application/json")
                        .path("schema")
                        .path("$ref")
                        .asText()
                        .endsWith("/Result"));
        for (String code : new String[] {"400", "401", "403", "404", "409", "503"}) {
            assertTrue(
                    operation(root, "/transfer/request", "post").path("responses").has(code), code);
        }
        assertTrue(operation(root, "/user/signup", "post").path("responses").has("409"));
        assertTrue(operation(root, "/user/login", "post").path("responses").has("401"));
    }

    @Test
    void specDoesNotLeakSecrets() throws Exception {
        String spec = docs();
        assertFalse(spec.contains(aesKey));
        assertFalse(spec.contains(jwtSecret));
    }

    @Test
    void swaggerUiIsReachableWithoutAuthentication() throws Exception {
        // 인증 헤더 없이 호출해도 UI 진입점이 고정된 위치로 리다이렉트되고 그 페이지가 열려야 한다.
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    // 끈 설정은 별도 컨텍스트라 정적 중첩 클래스로 분리한다.
    @SpringBootTest(
            properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    static class Disabled {
        @Autowired MockMvc mvc;

        @Test
        void disabledDocsReturnTheStandardNotFoundContract() throws Exception {
            for (String path : new String[] {"/v3/api-docs", "/swagger-ui.html"}) {
                mvc.perform(get(path))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.resultCode").value(404))
                        .andExpect(jsonPath("$.resultMsg").value("NOT_FOUND"));
            }
        }
    }
}
