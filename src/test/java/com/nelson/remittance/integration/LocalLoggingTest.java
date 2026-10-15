package com.nelson.remittance.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

// local 프로파일에서만 SQL 로그(DEBUG, 자리표시자 ?)·format_sql·DB 오류 로그(WARN)가 켜지고,
// 기본 설정에서는 SQL 로그가 설정되지 않고 SqlExceptionHelper가 OFF로 유지되는지 고정한다.
class LocalLoggingTest {
    // local 프로파일 파일이 실제로 로드되어 설정과 로거 효과가 모두 적용되는지 확인한다. 바인딩 값 로그는 기본으로 꺼져 있어야 한다.
    @SpringBootTest
    @ActiveProfiles({"local", "test"})
    static class LocalProfile {
        @Autowired Environment env;

        @Test
        void localProfileEnablesSqlLoggingFormatAndSqlExceptionWarn() {
            assertEquals("DEBUG", env.getProperty("logging.level.org.hibernate.SQL"));
            assertEquals(
                    "WARN",
                    env.getProperty(
                            "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper"));
            assertEquals("true", env.getProperty("spring.jpa.properties.hibernate.format_sql"));
            assertTrue(LoggerFactory.getLogger("org.hibernate.SQL").isDebugEnabled());
            assertEquals("DEBUG", env.getProperty("logging.level.com.zaxxer.hikari"));
            assertTrue(
                    LoggerFactory.getLogger("com.zaxxer.hikari.pool.HikariPool").isDebugEnabled());
        }

        @Test
        void localProfileKeepsBindValueLoggingOffByDefault() {
            assertNull(env.getProperty("logging.level.org.hibernate.orm.jdbc.bind"));
            assertFalse(LoggerFactory.getLogger("org.hibernate.orm.jdbc.bind").isTraceEnabled());
        }
    }

    // 프로파일 없이 실행하는 기본 환경은 SQL 로그 없음, SqlExceptionHelper OFF를 유지한다. 로거 전역 상태는 단언하지 않는다.
    @SpringBootTest
    @ActiveProfiles("test")
    static class DefaultProfile {
        @Autowired Environment env;

        @Test
        void defaultKeepsSqlLoggingUnsetAndSqlExceptionHelperOff() {
            assertNull(env.getProperty("logging.level.org.hibernate.SQL"));
            assertNull(env.getProperty("logging.level.com.zaxxer.hikari"));
            assertEquals(
                    "OFF",
                    env.getProperty(
                            "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper"));
        }
    }
}
