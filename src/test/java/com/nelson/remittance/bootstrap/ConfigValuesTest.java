package com.nelson.remittance.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigValuesTest {
    private static final String KEY = "remittance.test.value";

    @Test
    // 허용 범위의 양 끝은 받아들이고 바로 바깥은 거부한다.
    void integerAcceptsBoundariesAndRejectsOutside() {
        assertEquals(1, ConfigValues.integer(KEY, "1", 1, 3600));
        assertEquals(3600, ConfigValues.integer(KEY, "3600", 1, 3600));
        assertThrows(IllegalArgumentException.class, () -> ConfigValues.integer(KEY, "0", 1, 3600));
        assertThrows(
                IllegalArgumentException.class, () -> ConfigValues.integer(KEY, "3601", 1, 3600));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", " ", " 5", "5 ", "+5", "-5", "1.5", "5s", "1_0", "１２", "1234567890"})
    // 빈 값, 공백, 부호, 소수, 단위 문자, 전각 숫자, 10자리 이상은 범위와 무관하게 거부한다.
    void integerRejectsMalformedValues(String value) {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigValues.integer(KEY, value, 0, Integer.MAX_VALUE));
    }

    @Test
    // 선행 0은 기존 동작대로 10진수로 해석한다.
    void integerAcceptsLeadingZeros() {
        assertEquals(7, ConfigValues.integer(KEY, "007", 0, 100));
    }

    @Test
    // 오류 메시지는 설정 키와 허용 범위만 담고 입력값은 노출하지 않는다.
    void integerErrorMentionsKeyAndRangeButNotInput() {
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ConfigValues.integer(KEY, "secret-input", 1, 3600));
        assertEquals(KEY + "는 1 이상 3600 이하의 정수여야 합니다.", error.getMessage());
        assertFalse(error.getMessage().contains("secret-input"));
    }

    @Test
    // 소문자 true/false만 받아들인다.
    void boolAcceptsOnlyLowercaseTrueAndFalse() {
        assertTrue(ConfigValues.bool(KEY, "true"));
        assertFalse(ConfigValues.bool(KEY, "false"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "False", "maybe", "", " true", "1"})
    // 그 밖의 값은 설정 키를 담은 메시지로 거부한다.
    void boolRejectsOtherValues(String value) {
        var error =
                assertThrows(IllegalArgumentException.class, () -> ConfigValues.bool(KEY, value));
        assertEquals(KEY + "는 true 또는 false여야 합니다.", error.getMessage());
    }
}
