package com.moin.globalRemittance.config;

import com.moin.globalRemittance.controller.model.response.BaseResponse;
import com.moin.globalRemittance.exception.RemittanceException;
import com.moin.globalRemittance.exception.UnauthorizedException;
import com.moin.globalRemittance.exception.UserException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<BaseResponse> handleUnauthorizedException(UnauthorizedException ex) {
        BaseResponse response = BaseResponse.builder()
                .resultCode(HttpStatus.UNAUTHORIZED.value())
                .resultMsg(ex.getMessage())
                .build();
        return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<BaseResponse> handleRuntimeException(RuntimeException ex) {
        BaseResponse response = BaseResponse.builder()
                .resultCode(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .resultMsg(ex.getMessage())
                .build();
        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(UserException.class)
    public ResponseEntity<BaseResponse> handleUserIdDuplicatedException(UserException ex) {
        BaseResponse response = BaseResponse.builder()
                .resultCode(HttpStatus.CONFLICT.value())
                .resultMsg(ex.getMessage())
                .build();
        return new ResponseEntity<>(response, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(RemittanceException.class)
    public ResponseEntity<BaseResponse> handleUserIdDuplicatedException(RemittanceException ex) {
        BaseResponse response = BaseResponse.builder()
                .resultCode(HttpStatus.CONFLICT.value())
                .resultMsg(ex.getMessage())
                .build();
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }
}