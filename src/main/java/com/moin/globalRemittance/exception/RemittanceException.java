package com.moin.globalRemittance.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RemittanceException extends RuntimeException {
    private final String message;
}
