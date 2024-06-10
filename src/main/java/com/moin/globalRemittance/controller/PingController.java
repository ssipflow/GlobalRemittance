package com.moin.globalRemittance.controller;

import com.moin.globalRemittance.controller.model.response.BaseResponse;
import com.moin.globalRemittance.dto.IRemittanceDto;
import com.moin.globalRemittance.repository.custom.RemittanceRepository;
import com.moin.globalRemittance.repository.custom.TxTestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/public")
public class PingController {

    private final TxTestRepository repository;
    private final RemittanceRepository remittanceRepository;

    @GetMapping("/ping")
    @Transactional
    public BaseResponse ping() {
        Integer id = repository.txRequiresNewTest1();
        repository.txRequiresNewTest2(id);
        return BaseResponse.builder()
                .resultCode(200)
                .resultMsg("OK")
                .build();
    }
}
