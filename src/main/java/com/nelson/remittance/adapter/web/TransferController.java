package com.nelson.remittance.adapter.web;

import static com.nelson.remittance.domain.BusinessException.Code.INVALID_REQUEST;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.nelson.remittance.adapter.web.ApiModels.HistoryResult;
import com.nelson.remittance.adapter.web.ApiModels.HistoryView;
import com.nelson.remittance.adapter.web.ApiModels.QuoteInput;
import com.nelson.remittance.adapter.web.ApiModels.QuoteResult;
import com.nelson.remittance.adapter.web.ApiModels.QuoteView;
import com.nelson.remittance.adapter.web.ApiModels.RequestInput;
import com.nelson.remittance.adapter.web.ApiModels.Result;
import com.nelson.remittance.application.Ports;
import com.nelson.remittance.application.TransferUseCases;
import com.nelson.remittance.domain.BusinessException;
import jakarta.validation.Valid;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfer")
// 인증 주체를 송금 사용 사례에 전달한다. 요청 본문에 별도 사용자 식별자를 받지 않는다.
public class TransferController {
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    // 9999-12-31T23:59:59Z의 epoch 초다.
    private static final long MAX_CURSOR_SECONDS = 253_402_300_799L;
    // Idempotency-Key 허용 형식이다. 길이와 문자 집합을 제한해 저장 컬럼과 로그 오염을 막는다.
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{1,64}");
    private final TransferUseCases transfers;

    public TransferController(TransferUseCases transfers) {
        this.transfers = transfers;
    }

    @PostMapping(value = "/quote", consumes = "application/json", produces = "application/json")
    // 인증된 사용자로 견적을 생성하고 저장 식별자·환율·만료·수취액만 노출한다.
    public QuoteResult quote(
            @AuthenticationPrincipal Long actor, @Valid @RequestBody QuoteInput input) {
        return new QuoteResult(
                200,
                "OK",
                QuoteView.of(transfers.quote(actor, input.amount(), input.targetCurrency())));
    }

    @PostMapping(value = "/request", consumes = "application/json", produces = "application/json")
    // 문자열 견적 ID를 INT 저장 범위로 확인한 뒤 인증 사용자로 접수한다.
    // Idempotency-Key는 선택이며, 있으면 형식을 검증해 같은 키의 재시도가 한 번만 반영되게 한다.
    public Result request(
            @AuthenticationPrincipal Long actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RequestInput input) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new BusinessException(INVALID_REQUEST);
        }
        long quoteId;
        try {
            quoteId = Long.parseLong(input.quoteId());
        } catch (NumberFormatException error) {
            throw new BusinessException(INVALID_REQUEST);
        }
        if (quoteId > Integer.MAX_VALUE) {
            throw new BusinessException(INVALID_REQUEST);
        }
        transfers.request(actor, quoteId, idempotencyKey);
        return Result.ok();
    }

    @GetMapping(value = "/list", produces = "application/json")
    // 접수 이력 한 페이지와 오늘 전체 집계를 웹 응답으로 변환하며 해시나 암호문은 제외한다.
    // size·cursor는 선택이다. 숫자 변환 실패도 공통 JSON 오류로 내기 위해 문자열로 받아 직접 검증한다.
    public HistoryResult history(
            @AuthenticationPrincipal Long actor,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String cursor) {
        var result = transfers.history(actor, decodeCursor(cursor), pageSize(size));
        return new HistoryResult(
                200,
                "OK",
                result.user().userId(),
                result.user().name(),
                result.todayCount(),
                result.todayUsdAmount(),
                result.quotes().stream().map(HistoryView::of).toList(),
                encodeCursor(result.nextAfter()));
    }

    private static int pageSize(String size) {
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
    private static String encodeCursor(Ports.Quotes.Position position) {
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
    private static Ports.Quotes.Position decodeCursor(String cursor) {
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
            var position = new Ports.Quotes.Position(Instant.ofEpochSecond(seconds, nano), id);
            if (!cursor.equals(encodeCursor(position))) {
                throw new BusinessException(INVALID_REQUEST);
            }
            return position;
        } catch (IllegalArgumentException | DateTimeException error) {
            throw new BusinessException(INVALID_REQUEST);
        }
    }
}
