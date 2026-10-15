package com.nelson.remittance.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// H2 콘솔은 기본적으로 꺼져 있고 프레임이 DENY이며, 콘솔을 켠 경우에만 같은 출처 프레임(SAMEORIGIN)을 허용하는지 고정한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class H2ConsoleTest {
    @Autowired MockMvc mvc;

    @Test
    void defaultKeepsConsoleOffWithNotFoundContractAndDenyFrames() throws Exception {
        for (String path : new String[] {"/h2-console", "/h2-console/", "/no-such-path"}) {
            mvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(jsonPath("$.resultCode").value(404))
                    .andExpect(jsonPath("$.resultMsg").value("NOT_FOUND"));
        }
    }

    // 콘솔 서블릿은 MockMvc 밖에서 등록되므로 실제 포트로 요청한다. 켠 설정은 별도 컨텍스트라 중첩 클래스로 분리한다.
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "spring.h2.console.enabled=true")
    @ActiveProfiles("test")
    static class Enabled {
        @Autowired TestRestTemplate http;

        @Test
        void enabledConsoleIsServedAndAllowsSameOriginFrames() {
            ResponseEntity<String> response = http.getForEntity("/h2-console/", String.class);
            assertEquals(200, response.getStatusCode().value());
            assertEquals("SAMEORIGIN", response.getHeaders().getFirst("X-Frame-Options"));
        }

        @Test
        void otherPathsKeepTheSameOriginPolicyOnlyBecauseConsoleIsOn() {
            ResponseEntity<String> response = http.getForEntity("/no-such-path", String.class);
            assertEquals(404, response.getStatusCode().value());
            assertEquals("SAMEORIGIN", response.getHeaders().getFirst("X-Frame-Options"));
        }
    }

    // local 프로파일 파일이 실제로 로드되어 콘솔이 켜지는지 확인한다. DB는 test 프로파일의 메모리 DB가 덮어쓴다.
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles({"local", "test"})
    static class LocalProfile {
        @Autowired TestRestTemplate http;
        @Autowired Environment env;

        @Test
        void localProfileEnablesConsoleWithSameOriginFrames() {
            assertEquals("true", env.getProperty("spring.h2.console.enabled"));
            assertTrue(env.getProperty("spring.datasource.url").startsWith("jdbc:h2:mem:"));
            ResponseEntity<String> response = http.getForEntity("/h2-console/", String.class);
            assertEquals(200, response.getStatusCode().value());
            assertEquals("SAMEORIGIN", response.getHeaders().getFirst("X-Frame-Options"));
        }
    }
}
