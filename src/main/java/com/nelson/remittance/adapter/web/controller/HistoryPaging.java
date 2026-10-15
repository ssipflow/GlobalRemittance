package com.nelson.remittance.adapter.web.controller;

import static com.nelson.remittance.domain.exception.ErrorCode.INVALID_REQUEST;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.nelson.remittance.application.port.repository.QuotePosition;
import com.nelson.remittance.domain.exception.BusinessException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;

// 접수 이력의 페이지 크기와 불투명 커서 형식을 정하는 웹 경계 규칙이다.
final class HistoryPaging {
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    // 9999-12-31T23:59:59Z의 epoch 초다.
    private static final long MAX_CURSOR_SECONDS = 253_402_300_799L;

    private HistoryPaging() {}

    static int pageSize(String size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        // Integer.parseInt는 '+5'를 허용하므로 숫자만 먼저 확인한다.
        if (!size.matches("[0-9]+")) {
            throw new BusinessException(INVALID_REQUEST);
        }
        int value;
        try {
            value = Integer.parseInt(size);
        } catch (NumberFormatException error) {
            throw new BusinessException(INVALID_REQUEST);
        }
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw new BusinessException(INVALID_REQUEST);
        }
        return value;
    }

    // 커서는 "초.나노.ID"를 URL-safe Base64로 감싼 값이다. 클라이언트는 내용을 해석하지 않는다.
    static String encodeCursor(QuotePosition position) {
        if (position == null) {
            return null;
        }
        String raw =
                position.requestedTime().getEpochSecond()
                        + "."
                        + position.requestedTime().getNano()
                        + "."
                        + position.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(UTF_8));
    }

    // 형식·범위가 어긋난 커서는 모두 거부한다. 재인코딩 결과가 입력과 같아야 하므로 비정규 표기도 막는다.
    // 위치값은 정렬 기준일 뿐이며 조회는 항상 인증 사용자로 제한되어 권한 판단에 쓰이지 않는다.
    static QuotePosition decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), UTF_8);
            String[] parts = raw.split("\\.", -1);
            if (parts.length != 3) {
                throw new BusinessException(INVALID_REQUEST);
            }
            int nano = Integer.parseInt(parts[1]);
            long id = Long.parseLong(parts[2]);
            long seconds = Long.parseLong(parts[0]);
            // 초 값은 0001~9999년 안의 실제 접수 시각 범위로 제한해 DB 바인딩 오류를 막는다.
            if (seconds < 0
                    || seconds > MAX_CURSOR_SECONDS
                    || nano < 0
                    || nano > 999_999_999
                    || id < 1
                    || id > Integer.MAX_VALUE) {
                throw new BusinessException(INVALID_REQUEST);
            }
            var position = new QuotePosition(Instant.ofEpochSecond(seconds, nano), id);
            if (!cursor.equals(encodeCursor(position))) {
                throw new BusinessException(INVALID_REQUEST);
            }
            return position;
        } catch (IllegalArgumentException | DateTimeException error) {
            throw new BusinessException(INVALID_REQUEST);
        }
    }
}
