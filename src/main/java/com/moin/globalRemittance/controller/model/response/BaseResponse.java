package com.moin.globalRemittance.controller.model.response;

import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class BaseResponse {
    private Integer resultCode;
    private String resultMsg;
}
