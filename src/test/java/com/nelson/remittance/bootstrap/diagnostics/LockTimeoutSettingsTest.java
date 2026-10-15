package com.nelson.remittance.bootstrap.diagnostics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

// 잠금 대기 설정 검증은 컨텍스트와 DB 연결 전에 일어나므로 Spring 컨텍스트 없이 규칙과 안내문을 확인한다.
class LockTimeoutSettingsTest {
    private final LockTimeoutEnvironmentPostProcessor processor =
            new LockTimeoutEnvironmentPostProcessor();

    private void check(String value) {
        MockEnvironment env = new MockEnvironment();
        if (value != null) {
            env.setProperty("remittance.lock-timeout-ms", value);
        }
        processor.postProcessEnvironment(env, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "5000", "30000"})
    // 경계값을 포함한 허용 범위는 통과한다.
    void acceptsValuesInRange(String value) {
        assertDoesNotThrow(() -> check(value));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "", " ", "abc", "0", "-1", "30001", "5000ms", "1.5", "+5", " 5000", "00000", "5\n",
                "\u0665", "123456", "00005", "05"
            })
    // 비어 있거나 숫자 외 문자가 있거나 범위를 벗어난 값은 모두 거부한다.
    void rejectsInvalidValues(String value) {
        assertThrows(InvalidLockTimeoutException.class, () -> check(value));
    }

    @Test
    // 속성이 없으면 검사하지 않는다.
    void skipsWhenAbsent() {
        assertDoesNotThrow(() -> check(null));
    }

    @Test
    // 안내문은 정리된 값과 설정 이름·조치를 담는다.
    void analyzerDescribesInvalidValue() {
        FailureAnalysis analysis =
                new LockTimeoutFailureAnalyzer().analyze(new InvalidLockTimeoutException("abc"));
        assertNotNull(analysis);
        assertTrue(analysis.getDescription().contains("'abc'"));
        assertTrue(analysis.getAction().contains("REMITTANCE_LOCK_TIMEOUT_MS"));
    }

    @Test
    // 제어 문자와 긴 값은 정리·절단되어 로그 위조 수단이 되지 못한다.
    void analyzerSanitizesAndTruncatesValue() {
        InvalidLockTimeoutException error =
                new InvalidLockTimeoutException("a\nb" + "x".repeat(100));
        assertEquals(20, error.safeValue().length());
        assertTrue(error.safeValue().startsWith("a_b"));
        FailureAnalysis analysis = new LockTimeoutFailureAnalyzer().analyze(error);
        assertNotNull(analysis);
        assertFalse(analysis.getDescription().contains("a\nb"));
        assertFalse(analysis.getDescription().contains("x".repeat(21)));
        assertFalse(error.getMessage().contains("xxx"));
    }

    @Test
    // 관련 없는 예외는 다른 분석기에 맡긴다.
    void analyzerIgnoresUnrelatedFailure() {
        assertNull(new LockTimeoutFailureAnalyzer().analyze(new IllegalStateException("x")));
    }

    @Configuration
    static class EmptyConfig {}

    @Test
    // 잘못된 값이면 기동이 InvalidLockTimeoutException으로 실패한다.
    void invalidValueFailsStartup() {
        Throwable failure =
                assertThrows(
                        Throwable.class,
                        () ->
                                new SpringApplicationBuilder(EmptyConfig.class)
                                        .web(WebApplicationType.NONE)
                                        .run(
                                                "--remittance.lock-timeout-ms=abc",
                                                "--spring.main.banner-mode=off"));
        boolean found = false;
        for (Throwable t = failure;
                t != null && !found;
                t = t.getCause() == t ? null : t.getCause()) {
            found = t instanceof InvalidLockTimeoutException;
        }
        assertTrue(found, "원인 연쇄에 InvalidLockTimeoutException이 있어야 한다: " + failure);
    }

    @Test
    // spring.factories에 분석기와 후처리기가 실제로 등록되어 있어야 부팅 시 동작한다.
    void registeredInSpringFactories() {
        assertTrue(
                SpringFactoriesLoader.loadFactoryNames(FailureAnalyzer.class, null)
                        .contains(LockTimeoutFailureAnalyzer.class.getName()));
        assertTrue(
                SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, null)
                        .contains(LockTimeoutEnvironmentPostProcessor.class.getName()));
    }
}
