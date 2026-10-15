package com.nelson.remittance.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nelson.remittance.RemittanceApplication;
import com.nelson.remittance.application.port.rate.RateProvider;
import com.nelson.remittance.domain.exception.BusinessException;
import com.nelson.remittance.domain.exception.ErrorCode;
import com.nelson.remittance.domain.quote.RateSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// 환율 신선도 정책을 HTTP까지 확인한다. 설정이 다른 컨텍스트가 필요해 중첩 클래스마다 속성을 따로 준다.
class RatesApiTest {
    static final String EMAIL = "rates-user@example.com";

    // 가입·로그인·견적 호출 보조. 각 중첩 클래스가 상속한다.
    abstract static class Base {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper json;
        @Autowired JdbcTemplate jdbc;

        String token() throws Exception {
            mvc.perform(
                    post("/user/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    json.writeValueAsString(
                                            Map.of(
                                                    "userId",
                                                    EMAIL,
                                                    "password",
                                                    "test-password",
                                                    "name",
                                                    "홍길동",
                                                    "idType",
                                                    "REG_NO",
                                                    "idValue",
                                                    "example-identity"))));
            String body =
                    mvc.perform(
                                    post("/user/login")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    json.writeValueAsString(
                                                            Map.of(
                                                                    "userId",
                                                                    EMAIL,
                                                                    "password",
                                                                    "test-password"))))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            return json.readTree(body).get("token").asText();
        }

        org.springframework.test.web.servlet.ResultActions quote(String token) throws Exception {
            return mvc.perform(
                    post("/transfer/quote")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    json.writeValueAsString(
                                            Map.of(
                                                    "amount",
                                                    new BigDecimal("12345.67"),
                                                    "targetCurrency",
                                                    "JPY"))));
        }

        int quoteRows() {
            return jdbc.queryForObject("select count(*) from remittances", Integer.class);
        }
    }

    @SpringBootTest(
            properties = {
                "remittance.rates.source=simulated",
                "remittance.rates.simulated.unavailable=true"
            })
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @ExtendWith(OutputCaptureExtension.class)
    @Nested
    class Unavailable extends Base {
        @Test
        // 장애는 503 RATE_UNAVAILABLE이며 Retry-After 없이 견적을 만들지 않고 코드만 로그에 남긴다.
        void rejectsWithoutLeakingValues(CapturedOutput output) throws Exception {
            String token = token();
            var result =
                    quote(token)
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.resultMsg").value("RATE_UNAVAILABLE"))
                            .andReturn();
            assertNull(result.getResponse().getHeader("Retry-After"));
            assertEquals(0, quoteRows());
            String log = output.getAll();
            assertTrue(log.contains("RATE_UNAVAILABLE"));
            // 요청에 쓴 이메일·비밀번호·이름·식별번호·토큰·금액은 로그에 남지 않아야 한다.
            for (String secret :
                    new String[] {
                        EMAIL,
                        EMAIL.substring(0, EMAIL.indexOf('@')),
                        "test-password",
                        "홍길동",
                        "example-identity",
                        token,
                        "12345.67"
                    }) {
                assertFalse(log.contains(secret), "로그에 노출됨: " + secret.length() + "자 값");
            }
        }
    }

    @SpringBootTest(
            properties = {
                "remittance.rates.source=simulated",
                "remittance.rates.simulated.age-seconds=120"
            })
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Nested
    class Stale extends Base {
        @Test
        void rejectsOldRate() throws Exception {
            quote(token())
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.resultMsg").value("RATE_STALE"));
            assertEquals(0, quoteRows());
        }
    }

    @SpringBootTest(
            properties = {
                "remittance.rates.source=simulated",
                "remittance.rates.simulated.age-seconds=30"
            })
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Nested
    class WithinLimit extends Base {
        @Test
        void acceptsRateWithinDefaultMaxAge() throws Exception {
            quote(token()).andExpect(status().isOk());
            assertEquals(1, quoteRows());
        }
    }

    @SpringBootTest(
            properties = {
                "remittance.rates.source=simulated",
                "remittance.rates.max-age-seconds=1",
                "remittance.rates.simulated.age-seconds=2"
            })
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Nested
    class ConfiguredMaxAge extends Base {
        @Test
        // 허용 지연 설정이 실제 판정에 반영된다.
        void honorsMaxAgeSetting() throws Exception {
            quote(token())
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.resultMsg").value("RATE_STALE"));
        }
    }

    // 장애 토글이 가능한 공급처다. 발급된 견적의 접수가 환율 상태와 무관함을 확인한다.
    static class SwitchableRates implements RateProvider {
        final AtomicBoolean down = new AtomicBoolean();
        final Clock clock = Clock.systemUTC();

        @Override
        public RateSnapshot rate(String currency) {
            if (down.get()) {
                throw new BusinessException(ErrorCode.RATE_UNAVAILABLE);
            }
            return new RateSnapshot(
                    new BigDecimal("USD".equals(currency) ? "1000" : "10"), clock.instant());
        }
    }

    @TestConfiguration
    static class SwitchableConfig {
        @Bean
        @Primary
        SwitchableRates switchableRates() {
            return new SwitchableRates();
        }
    }

    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Import(SwitchableConfig.class)
    @Nested
    class OutageAfterIssue extends Base {
        @Autowired SwitchableRates rates;

        @Test
        void issuedQuoteStaysRequestableDuringOutage() throws Exception {
            String token = token();
            String body =
                    quote(token)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            String quoteId = json.readTree(body).get("quote").get("quoteId").asText();

            rates.down.set(true);
            mvc.perform(
                            post("/transfer/request")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsString(Map.of("quoteId", quoteId))))
                    .andExpect(status().isOk());
            quote(token)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.resultMsg").value("RATE_UNAVAILABLE"));
        }
    }

    @Nested
    class StartupValidation {
        // 메모리 H2(test 프로파일)로 컨텍스트를 띄워 잘못된 설정이 기동을 막는지 본다.
        private void start(String... overrides) {
            var args = new java.util.ArrayList<String>();
            args.add("--spring.profiles.active=test");
            args.add("--server.port=0");
            for (String o : overrides) {
                args.add("--" + o);
            }
            new SpringApplicationBuilder(RemittanceApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .run(args.toArray(String[]::new))
                    .close();
        }

        @ParameterizedTest
        @CsvSource({
            "remittance.rates.source,live",
            "remittance.rates.max-age-seconds,0",
            "remittance.rates.max-age-seconds,3601",
            "remittance.rates.max-age-seconds,abc",
            "remittance.rates.simulated.age-seconds,-1",
            "remittance.rates.simulated.age-seconds,abc",
            "remittance.rates.simulated.unavailable,maybe"
        })
        void rejectsInvalidSettings(String key, String value) {
            // simulated 값은 source=simulated일 때만 검증된다.
            String[] args =
                    key.startsWith("remittance.rates.simulated")
                            ? new String[] {"remittance.rates.source=simulated", key + "=" + value}
                            : new String[] {key + "=" + value};
            Throwable error = assertThrows(Throwable.class, () -> start(args));
            // 포트 충돌 등 다른 이유의 실패와 구분하기 위해 원인 연쇄에서 설정 검증 예외를 찾는다.
            IllegalArgumentException cause = null;
            for (Throwable t = error; t != null && cause == null; t = t.getCause()) {
                if (t instanceof IllegalArgumentException e
                        && e.getMessage() != null
                        && e.getMessage().contains(key)) {
                    cause = e;
                }
            }
            assertNotNull(cause, "설정 키를 담은 IllegalArgumentException이 없음: " + key);
        }

        @Test
        void acceptsBoundaryMaxAge() {
            start("remittance.rates.max-age-seconds=1");
            start("remittance.rates.max-age-seconds=3600");
        }
    }
}
