package com.nelson.remittance.bootstrap.diagnostics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nelson.remittance.bootstrap.diagnostics.InvalidSocketTimeoutException.Reason;
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

// 소켓 타임아웃 설정 검증은 컨텍스트와 DB 연결 전에 일어나므로 Spring 컨텍스트 없이 규칙과 안내문을 확인한다.
class SocketTimeoutSettingsTest {
    private final SocketTimeoutEnvironmentPostProcessor processor =
            new SocketTimeoutEnvironmentPostProcessor();

    private void check(String seconds, String lockMs) {
        MockEnvironment env = new MockEnvironment();
        if (seconds != null) {
            env.setProperty("remittance.db-socket-timeout-seconds", seconds);
        }
        if (lockMs != null) {
            env.setProperty("remittance.lock-timeout-ms", lockMs);
        }
        processor.postProcessEnvironment(env, null);
    }

    private Reason reasonOf(String seconds, String lockMs) {
        return assertThrows(InvalidSocketTimeoutException.class, () -> check(seconds, lockMs))
                .reason();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "6", "31", "60", "3600"})
    // 기본 잠금 대기(5000ms)보다 큰 경계값을 포함해 통과한다.
    void acceptsValidValues(String value) {
        if (value.equals("1")) {
            // 1초는 잠금 대기 한도를 더 짧게 둘 때만 유효하다.
            assertDoesNotThrow(() -> check(value, "999"));
        } else if (value.equals("6")) {
            assertDoesNotThrow(() -> check(value, "5000"));
        } else {
            assertDoesNotThrow(() -> check(value, "30000"));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "", " ", "abc", "0", "-1", "60s", "1.5", "+5", " 60", "00060", "060", "60\n", "٥",
                "12345"
            })
    // 비어 있거나 숫자 외 문자·부호·선행 0이 있거나 5자리 이상인 값은 형식 위반이다.
    void rejectsBadFormat(String value) {
        assertEquals(Reason.FORMAT, reasonOf(value, "5000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3601", "9999"})
    // 형식은 맞지만 3600초를 넘으면 범위 위반이다.
    void rejectsOutOfRange(String value) {
        assertEquals(Reason.RANGE, reasonOf(value, "5000"));
    }

    @Test
    // 잠금 대기 한도(ms)와 같거나 작으면 거부하고, 한 단위 위는 허용한다.
    void rejectsNotAboveLockTimeout() {
        assertEquals(Reason.NOT_ABOVE_LOCK_TIMEOUT, reasonOf("5", "5000"));
        assertEquals(Reason.NOT_ABOVE_LOCK_TIMEOUT, reasonOf("1", "30000"));
        assertEquals(Reason.NOT_ABOVE_LOCK_TIMEOUT, reasonOf("30", "30000"));
        assertDoesNotThrow(() -> check("31", "30000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "30001", "5s"})
    // 잠금 대기 값이 잘못됐으면 비교하지 않는다(그 오류는 잠금 대기 검증기가 먼저·따로 안내한다).
    void skipsComparisonWhenLockTimeoutIsInvalid(String lockMs) {
        assertDoesNotThrow(() -> check("1", lockMs));
    }

    @Test
    // 소켓 타임아웃 속성이 없으면 검사하지 않는다.
    void skipsWhenAbsent() {
        assertDoesNotThrow(() -> check(null, null));
        assertDoesNotThrow(() -> check(null, "5000"));
    }

    @Test
    // 안내문은 어느 제약을 어겼는지, 정리된 값과 설정 이름·조치를 담는다.
    void analyzerDescribesEachReason() {
        for (Reason reason : Reason.values()) {
            FailureAnalysis analysis =
                    new SocketTimeoutFailureAnalyzer()
                            .analyze(new InvalidSocketTimeoutException("abc", reason));
            assertNotNull(analysis);
            assertTrue(analysis.getDescription().contains("'abc'"));
            assertTrue(analysis.getAction().contains("REMITTANCE_DB_SOCKET_TIMEOUT_SECONDS"));
        }
        assertTrue(
                new SocketTimeoutFailureAnalyzer()
                        .analyze(
                                new InvalidSocketTimeoutException(
                                        "5", Reason.NOT_ABOVE_LOCK_TIMEOUT))
                        .getDescription()
                        .contains("잠금 대기"));
        assertTrue(
                new SocketTimeoutFailureAnalyzer()
                        .analyze(new InvalidSocketTimeoutException("9999", Reason.RANGE))
                        .getDescription()
                        .contains("1~3600"));
    }

    @Test
    // 제어 문자와 긴 값은 정리·절단되어 로그 위조 수단이 되지 못한다.
    void analyzerSanitizesAndTruncatesValue() {
        InvalidSocketTimeoutException error =
                new InvalidSocketTimeoutException("a\nb" + "x".repeat(100), Reason.FORMAT);
        assertEquals(20, error.safeValue().length());
        assertTrue(error.safeValue().startsWith("a_b"));
        FailureAnalysis analysis = new SocketTimeoutFailureAnalyzer().analyze(error);
        assertNotNull(analysis);
        assertFalse(analysis.getDescription().contains("a\nb"));
        assertFalse(analysis.getDescription().contains("x".repeat(21)));
        assertFalse(error.getMessage().contains("xxx"));
    }

    @Test
    // 관련 없는 예외는 다른 분석기에 맡긴다.
    void analyzerIgnoresUnrelatedFailure() {
        assertNull(new SocketTimeoutFailureAnalyzer().analyze(new IllegalStateException("x")));
    }

    @Configuration
    static class EmptyConfig {}

    private static boolean hasInvalidSocketTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof InvalidSocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static void run(String... args) {
        var all = new java.util.ArrayList<>(java.util.List.of(args));
        all.add("--spring.main.banner-mode=off");
        new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.NONE)
                .run(all.toArray(String[]::new))
                .close();
    }

    @Test
    // 잘못된 값이나 잠금 대기 이하의 값이면 기동이 InvalidSocketTimeoutException으로 실패한다.
    void invalidValueFailsStartup() {
        Throwable bad =
                assertThrows(
                        Throwable.class, () -> run("--remittance.db-socket-timeout-seconds=abc"));
        assertTrue(hasInvalidSocketTimeout(bad), "원인 연쇄: " + bad);
        Throwable low =
                assertThrows(
                        Throwable.class,
                        () ->
                                run(
                                        "--remittance.db-socket-timeout-seconds=5",
                                        "--remittance.lock-timeout-ms=5000"));
        assertTrue(hasInvalidSocketTimeout(low), "원인 연쇄: " + low);
    }

    @Test
    // 기본 설정(소켓 60초, 잠금 대기 5000ms)의 기동에는 영향이 없다.
    void defaultSettingsStartNormally() {
        assertDoesNotThrow(() -> run());
    }

    @Test
    // spring.factories에 분석기와 후처리기가 실제로 등록되어 있어야 부팅 시 동작한다.
    void registeredInSpringFactories() {
        assertTrue(
                SpringFactoriesLoader.loadFactoryNames(FailureAnalyzer.class, null)
                        .contains(SocketTimeoutFailureAnalyzer.class.getName()));
        assertTrue(
                SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, null)
                        .contains(SocketTimeoutEnvironmentPostProcessor.class.getName()));
    }
}
