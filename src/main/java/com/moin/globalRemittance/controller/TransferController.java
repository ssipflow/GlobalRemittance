package com.moin.globalRemittance.controller;

import com.moin.globalRemittance.controller.model.QuoteBaseDto;
import com.moin.globalRemittance.controller.model.QuoteDto;
import com.moin.globalRemittance.controller.model.response.BaseResponse;
import com.moin.globalRemittance.controller.model.response.HistoryResponse;
import com.moin.globalRemittance.controller.model.response.QuoteResponse;
import com.moin.globalRemittance.dto.IHistoryDto;
import com.moin.globalRemittance.dto.IQuoteBaseDto;
import com.moin.globalRemittance.dto.IUserDto;
import com.moin.globalRemittance.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/transfer")
public class TransferController {

    private final TransferService transferService;

    @PostMapping("/quote")
    public ResponseEntity<BaseResponse> createQuote(@Valid @RequestBody QuoteDto param) {
        IUserDto user = (IUserDto) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();

        IQuoteBaseDto quote = transferService.createQuote(user.getId(), param);

        return ResponseEntity.ok(QuoteResponse.builder()
                .resultCode(200)
                .resultMsg("OK")
                .quote(quote)
                .build());
    }

    @PostMapping("/request")
    public ResponseEntity<BaseResponse> requestRemittance(@Valid @RequestBody QuoteBaseDto param) {
        IUserDto user = (IUserDto) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();

        transferService.requestRemittance(user, param.getQuoteId());

        return ResponseEntity.ok(BaseResponse.builder()
                .resultCode(200)
                .resultMsg("OK")
                .build());
    }

    @GetMapping("/list")
    public ResponseEntity<BaseResponse> getUserRemittanceHistory() {
        IUserDto user = (IUserDto) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();

        IHistoryDto remittanceHistory = transferService.getUserRemittanceHistory(user);

        return ResponseEntity.ok(HistoryResponse.builder()
                .resultCode(200)
                .resultMsg("OK")
                .userId(remittanceHistory.getUserId())
                .name(remittanceHistory.getName())
                .todayTransferCount(remittanceHistory.getTodayTransferCount())
                .todayTransferUsdAmount(remittanceHistory.getTodayTransferUsdAmount())
                .history(remittanceHistory.getHistory())
                .build());
    }
}
