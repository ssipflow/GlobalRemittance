package com.nelson.remittance.adapter.web;

import static com.nelson.remittance.adapter.web.ApiModels.Result;

import com.nelson.remittance.domain.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> business(BusinessException error) {
        HttpStatus status =
                switch (error.code()) {
                    case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
                    case FORBIDDEN -> HttpStatus.FORBIDDEN;
                    case QUOTE_NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case DUPLICATE_USER, ALREADY_REQUESTED -> HttpStatus.CONFLICT;
                    case UNKNOWN_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return response(status.value(), error.code().name());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> unexpected(Exception error) {
        // Spring MVC의 검증/라우팅 오류도 같은 JSON 계약으로 반환한다. 입력과 스택은 응답에 노출하지 않는다.
        if (error instanceof ErrorResponse known && known.getStatusCode().is4xxClientError()) {
            int status = known.getStatusCode().value();
            String code =
                    switch (status) {
                        case 404 -> "NOT_FOUND";
                        case 405 -> "METHOD_NOT_ALLOWED";
                        case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                        case 406 -> "NOT_ACCEPTABLE";
                        default -> "INVALID_REQUEST";
                    };
            return response(status, code);
        }
        // 본문 파싱 오류는 일부 Spring 예외에서 ErrorResponse를 구현하지 않는다.
        if (error instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            return response(400, "INVALID_REQUEST");
        }
        return response(500, "UNKNOWN_ERROR");
    }

    private static ResponseEntity<Result> response(int status, String code) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Result(status, code));
    }
}
