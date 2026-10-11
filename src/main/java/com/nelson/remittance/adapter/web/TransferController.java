package com.nelson.remittance.adapter.web;

import static com.nelson.remittance.domain.BusinessException.Code.INVALID_REQUEST;

import com.nelson.remittance.adapter.web.ApiModels.HistoryResult;
import com.nelson.remittance.adapter.web.ApiModels.HistoryView;
import com.nelson.remittance.adapter.web.ApiModels.QuoteInput;
import com.nelson.remittance.adapter.web.ApiModels.QuoteResult;
import com.nelson.remittance.adapter.web.ApiModels.QuoteView;
import com.nelson.remittance.adapter.web.ApiModels.RequestInput;
import com.nelson.remittance.adapter.web.ApiModels.Result;
import com.nelson.remittance.application.TransferUseCases;
import com.nelson.remittance.domain.BusinessException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfer")
// 인증 주체를 송금 사용 사례에 전달한다. 요청 본문에 별도 사용자 식별자를 받지 않는다.
public class TransferController {
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
    public Result request(
            @AuthenticationPrincipal Long actor, @Valid @RequestBody RequestInput input) {
        long quoteId;
        try {
            quoteId = Long.parseLong(input.quoteId());
        } catch (NumberFormatException error) {
            throw new BusinessException(INVALID_REQUEST);
        }
        if (quoteId > Integer.MAX_VALUE) {
            throw new BusinessException(INVALID_REQUEST);
        }
        transfers.request(actor, quoteId);
        return Result.ok();
    }

    @GetMapping(value = "/list", produces = "application/json")
    // 전체 접수 이력과 오늘 집계를 웹 응답으로 변환하며 해시나 암호문은 제외한다.
    public HistoryResult history(@AuthenticationPrincipal Long actor) {
        var result = transfers.history(actor);
        return new HistoryResult(
                200,
                "OK",
                result.user().userId(),
                result.user().name(),
                result.todayCount(),
                result.todayUsdAmount(),
                result.quotes().stream().map(HistoryView::of).toList());
    }
}
