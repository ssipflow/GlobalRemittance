package com.moin.globalRemittance.controller.model.response;

import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class LoginResponse extends BaseResponse {
    private String token;
}
