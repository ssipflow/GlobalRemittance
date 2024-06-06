package com.moin.globalRemittance.controller;

import com.moin.globalRemittance.controller.model.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/public")
public class PingController {
    @GetMapping("/ping")
    public BaseResponse ping() {
        return BaseResponse.builder()
                .resultCode(200)
                .resultMsg("OK")
                .build();
    }
}
