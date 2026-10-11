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
public class TransferController {
    private final TransferUseCases transfers;

    public TransferController(TransferUseCases transfers) {
        this.transfers = transfers;
    }

    @PostMapping(value = "/quote", consumes = "application/json", produces = "application/json")
    public QuoteResult quote(
            @AuthenticationPrincipal Long actor, @Valid @RequestBody QuoteInput input) {
        return new QuoteResult(
                200,
                "OK",
                QuoteView.of(transfers.quote(actor, input.amount(), input.targetCurrency())));
    }

    @PostMapping(value = "/request", consumes = "application/json", produces = "application/json")
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
