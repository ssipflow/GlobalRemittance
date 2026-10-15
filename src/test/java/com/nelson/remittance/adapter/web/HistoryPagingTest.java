package com.nelson.remittance.adapter.web;

import static com.nelson.remittance.domain.BusinessException.Code.INVALID_REQUEST;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nelson.remittance.application.port.QuotePosition;
import com.nelson.remittance.domain.BusinessException;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Spring 없이 페이지 크기와 커서 규칙의 허용·거부 경계를 고정한다.
class HistoryPagingTest {
    private static final long MAX_SECONDS = 253_402_300_799L;

    private static String cursor(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(UTF_8));
    }

    private static void assertInvalidSize(String size) {
        assertThatThrownBy(() -> HistoryPaging.pageSize(size))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(INVALID_REQUEST));
    }

    private static void assertInvalidCursor(String value) {
        assertThatThrownBy(() -> HistoryPaging.decodeCursor(value))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(INVALID_REQUEST));
    }

    @Test
    void pageSizeDefaultsAndAcceptsBounds() {
        assertThat(HistoryPaging.pageSize(null)).isEqualTo(50);
        assertThat(HistoryPaging.pageSize("1")).isEqualTo(1);
        assertThat(HistoryPaging.pageSize("100")).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0",
                "101",
                "",
                "abc",
                "+5",
                "-1",
                "1.5",
                " 5",
                " ",
                "٥",
                "５",
                "99999999999999999999"
            })
    void pageSizeRejectsInvalid(String size) {
        assertInvalidSize(size);
    }

    @Test
    void cursorNullStaysNull() {
        assertThat(HistoryPaging.encodeCursor(null)).isNull();
        assertThat(HistoryPaging.decodeCursor(null)).isNull();
    }

    @Test
    void cursorRoundTrips() {
        long[] seconds = {0, 1, 1_700_000_000L, MAX_SECONDS};
        int[] nanos = {0, 123_456_000, 999_999_999};
        long[] ids = {1, 42, Integer.MAX_VALUE};
        for (long s : seconds) {
            for (int n : nanos) {
                for (long id : ids) {
                    var position = new QuotePosition(Instant.ofEpochSecond(s, n), id);
                    var encoded = HistoryPaging.encodeCursor(position);
                    assertThat(HistoryPaging.decodeCursor(encoded)).isEqualTo(position);
                }
            }
        }
    }

    @Test
    void cursorRejectsNonCanonicalForms() {
        assertInvalidCursor(cursor("001.0.1"));
        assertInvalidCursor(cursor("1.00.1"));
        assertInvalidCursor(cursor("1.0.001"));
        assertInvalidCursor(cursor("+1.0.1"));
        // 패딩이 붙은 표기는 재인코딩 결과와 달라 거부된다.
        assertInvalidCursor(Base64.getUrlEncoder().encodeToString("1.0.1".getBytes(UTF_8)));
    }

    @Test
    void cursorRejectsMalformed() {
        assertInvalidCursor("!!!");
        assertInvalidCursor("");
        assertInvalidCursor(cursor("1.2"));
        assertInvalidCursor(cursor("1.2.3.4"));
        assertInvalidCursor(cursor("a.0.1"));
        assertInvalidCursor(cursor("1.b.1"));
        assertInvalidCursor(cursor("1.0.c"));
    }

    @Test
    void cursorRangeBoundaries() {
        assertThat(HistoryPaging.decodeCursor(cursor("0.0.1"))).isNotNull();
        assertInvalidCursor(cursor("-1.0.1"));
        assertThat(HistoryPaging.decodeCursor(cursor(MAX_SECONDS + ".0.1"))).isNotNull();
        assertInvalidCursor(cursor((MAX_SECONDS + 1) + ".0.1"));
        assertInvalidCursor(cursor("1.-1.1"));
        assertInvalidCursor(cursor("1.1000000000.1"));
        assertThat(HistoryPaging.decodeCursor(cursor("1.999999999.1"))).isNotNull();
        assertInvalidCursor(cursor("1.0.0"));
        assertInvalidCursor(cursor("1.0." + ((long) Integer.MAX_VALUE + 1)));
        assertThat(HistoryPaging.decodeCursor(cursor("1.0." + Integer.MAX_VALUE))).isNotNull();
    }
}
