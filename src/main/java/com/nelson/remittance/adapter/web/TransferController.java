package com.nelson.remittance.adapter.web;

import static com.nelson.remittance.domain.exception.ErrorCode.INVALID_REQUEST;

import com.nelson.remittance.adapter.web.dto.HistoryResult;
import com.nelson.remittance.adapter.web.dto.HistoryView;
import com.nelson.remittance.adapter.web.dto.QuoteInput;
import com.nelson.remittance.adapter.web.dto.QuoteResult;
import com.nelson.remittance.adapter.web.dto.QuoteView;
import com.nelson.remittance.adapter.web.dto.RequestInput;
import com.nelson.remittance.adapter.web.dto.Result;
import com.nelson.remittance.application.transfer.TransferUseCases;
import com.nelson.remittance.domain.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@Tag(name = "송금", description = "견적 생성, 송금 접수, 접수 이력 조회. 로그인 토큰이 필요하다.")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
// 인증 주체를 송금 사용 사례에 전달한다. 요청 본문에 별도 사용자 식별자를 받지 않는다.
public class TransferController {
    // Idempotency-Key 허용 형식이다. 길이와 문자 집합을 제한해 저장 컬럼과 로그 오염을 막는다.
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{1,64}");
    private final TransferUseCases transfers;

    public TransferController(TransferUseCases transfers) {
        this.transfers = transfers;
    }

    @Operation(
            summary = "견적 생성",
            description = "KRW 금액과 수취 통화(USD, JPY)로 견적을 만든다. 만료시각은 생성 후 10분이다.")
    @ApiResponse(
            responseCode = "400",
            description = "입력 오류(INVALID_REQUEST) 또는 양수가 아니거나 수수료 차감 후 음수(NEGATIVE_NUMBER)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @ApiResponse(
            responseCode = "401",
            description = "토큰 누락·오류·만료(UNAUTHORIZED)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "환율 조회 실패(RATE_UNAVAILABLE) 또는 환율 기준 시각이 허용 범위를 벗어남(RATE_STALE). 견적은 발급되지 않는다.",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @PostMapping(value = "/quote", consumes = "application/json", produces = "application/json")
    // 인증된 사용자로 견적을 생성하고 저장 식별자·환율·만료·수취액만 노출한다.
    public QuoteResult quote(
            @AuthenticationPrincipal Long actor, @Valid @RequestBody QuoteInput input) {
        return new QuoteResult(
                200,
                "OK",
                QuoteView.of(transfers.quote(actor, input.amount(), input.targetCurrency())));
    }

    @Operation(
            summary = "송금 접수",
            description = "현재 사용자 소유의 유효한 견적을 접수한다. Idempotency-Key를 보내면 같은 키의 재시도는 한 번만 반영된다.")
    @ApiResponse(
            responseCode = "400",
            description = "형식 오류(INVALID_REQUEST), 견적 만료(QUOTE_EXPRIED), 일일 한도 초과(LIMIT_EXCESS)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @ApiResponse(
            responseCode = "401",
            description = "토큰 누락·오류·만료(UNAUTHORIZED)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @ApiResponse(
            responseCode = "403",
            description = "타인의 견적(FORBIDDEN)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @ApiResponse(
            responseCode = "404",
            description = "견적 없음(QUOTE_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @ApiResponse(
            responseCode = "409",
            description = "이미 접수한 견적(ALREADY_REQUESTED)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @ApiResponse(
            responseCode = "422",
            description = "같은 키를 다른 견적에 사용(IDEMPOTENCY_KEY_REUSED)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @ApiResponse(
            responseCode = "503",
            description = "사용자 잠금 대기 초과(LOCK_TIMEOUT). 같은 Idempotency-Key로 재시도할 수 있다",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @PostMapping(value = "/request", consumes = "application/json", produces = "application/json")
    // 문자열 견적 ID를 INT 저장 범위로 확인한 뒤 인증 사용자로 접수한다.
    // Idempotency-Key는 선택이며, 있으면 형식을 검증해 같은 키의 재시도가 한 번만 반영되게 한다.
    public Result request(
            @AuthenticationPrincipal Long actor,
            @Parameter(
                            description =
                                    "재시도 구분용 선택 키. 영문·숫자·`.`·`_`·`~`·`-` 1~64자(UUID 권장)이며 사용자별로 적용된다.",
                            example = "6f1c2b7e-0a54-4d1e-9b7a-3c2d8e1f4a90")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
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

    @Operation(
            summary = "접수 이력 조회",
            description = "접수가 끝난 항목을 최신순으로 한 페이지씩 반환한다. 오늘 집계는 KST 기준 전체다.")
    @ApiResponse(
            responseCode = "400",
            description = "잘못된 cursor 또는 범위를 벗어난 size(INVALID_REQUEST)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @ApiResponse(
            responseCode = "401",
            description = "토큰 누락·오류·만료(UNAUTHORIZED)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @GetMapping(value = "/list", produces = "application/json")
    // 접수 이력 한 페이지와 오늘 전체 집계를 웹 응답으로 변환하며 해시나 암호문은 제외한다.
    // size·cursor는 선택이다. 숫자 변환 실패도 공통 JSON 오류로 내기 위해 문자열로 받아 직접 검증한다.
    public HistoryResult history(
            @AuthenticationPrincipal Long actor,
            @Parameter(description = "한 페이지 항목 수. 숫자만 허용하며 1~100, 생략하면 50이다.", example = "20")
                    @RequestParam(required = false)
                    String size,
            @Parameter(description = "이전 응답의 nextCursor. 불투명 문자열이며 생략하면 첫 페이지다.")
                    @RequestParam(required = false)
                    String cursor) {
        var result =
                transfers.history(
                        actor, HistoryPaging.decodeCursor(cursor), HistoryPaging.pageSize(size));
        return new HistoryResult(
                200,
                "OK",
                result.user().userId(),
                result.user().name(),
                result.todayCount(),
                result.todayUsdAmount(),
                result.quotes().stream().map(HistoryView::of).toList(),
                HistoryPaging.encodeCursor(result.nextAfter()));
    }
}
