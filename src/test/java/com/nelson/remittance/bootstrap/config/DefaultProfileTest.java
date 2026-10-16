package com.nelson.remittance.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

// 프로파일을 지정하지 않은 기본 실행이 PostgreSQL 설정을 고르고, 프로파일을 지정하면 H2 설정으로 남는지 확인한다.
// DB·JPA·Flyway 자동 구성을 제외해 Docker나 DB 없이 설정 해석만 검증한다.
// 셸에 SPRING_PROFILES_ACTIVE·SPRING_DATASOURCE_URL·REMITTANCE_DB_URL이 설정돼 있으면 결과가 달라질 수 있다.
class DefaultProfileTest {
    @Configuration
    static class Empty {}

    // 설정 파일만 읽는 최소 컨텍스트를 띄워 주어진 함수로 환경 값을 읽는다. 테스트가 쓸 값만 인자로 넘긴다.
    private static <T> T inEnvironment(
            Function<ConfigurableApplicationContext, T> reader, String... profiles) {
        SpringApplicationBuilder builder =
                new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE);
        if (profiles.length > 0) {
            builder.profiles(profiles);
        }
        try (ConfigurableApplicationContext context =
                builder.run(
                        "--spring.autoconfigure.exclude="
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                        "--spring.main.banner-mode=off",
                        "--remittance.aes-key=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
                        "--remittance.jwt-secret=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")) {
            return reader.apply(context);
        }
    }

    @Test
    // 프로파일이 없으면 postgres가 활성화되어 PostgreSQL URL·전용 마이그레이션·lock_timeout 초기화가 적용된다.
    void defaultsToPostgres() {
        inEnvironment(
                context -> {
                    var env = context.getEnvironment();
                    assertTrue(
                            env.getProperty("spring.datasource.url")
                                    .startsWith("jdbc:postgresql:"));
                    assertEquals(
                            "classpath:db/postgresql/migration",
                            env.getProperty("spring.flyway.locations"));
                    assertEquals(
                            "SET lock_timeout = 5000",
                            env.getProperty("spring.datasource.hikari.connection-init-sql"));
                    assertEquals("false", env.getProperty("spring.flyway.baseline-on-migrate"));
                    return null;
                });
    }

    @Test
    // 프로파일을 지정하면 기본값이 적용되지 않아 기존 H2 파일 DB 설정(./data)으로 실행된다.
    void explicitProfileKeepsH2File() {
        inEnvironment(
                context -> {
                    var env = context.getEnvironment();
                    assertTrue(
                            env.getProperty("spring.datasource.url")
                                    .startsWith("jdbc:h2:file:./data/remittance"));
                    assertEquals("true", env.getProperty("spring.flyway.baseline-on-migrate"));
                    return null;
                },
                "h2");
    }
}
