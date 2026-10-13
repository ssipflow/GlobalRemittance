package com.nelson.remittance.adapter.web;

import static com.nelson.remittance.adapter.web.ApiModels.Result;

import com.nelson.remittance.domain.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
// 업무·검증·라우팅 예외를 일관된 JSON 오류로 변환하고 예상하지 못한 원인은 숨긴다.
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    // 업무 오류 코드에 맞는 HTTP 상태를 결정하고 내부 예외 내용 대신 코드만 반환한다.
    public ResponseEntity<Result> business(BusinessException error, HttpServletRequest request) {
        HttpStatus status =
                switch (error.code()) {
                    case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
                    case FORBIDDEN -> HttpStatus.FORBIDDEN;
                    case QUOTE_NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case DUPLICATE_USER, ALREADY_REQUESTED -> HttpStatus.CONFLICT;
                    case IDEMPOTENCY_KEY_REUSED -> HttpStatus.UNPROCESSABLE_ENTITY;
                    case LOCK_TIMEOUT -> HttpStatus.SERVICE_UNAVAILABLE;
                    case UNKNOWN_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
                    default -> HttpStatus.BAD_REQUEST;
                };
        // 요청 ID는 MDC로 로그 패턴에 붙는다. 업무 오류는 코드만 남기고 예외 메시지·객체는 남기지 않는다.
        switch (error.code()) {
            case LOCK_TIMEOUT ->
                    LOG.warn("business error code={} {}", error.code(), where(request));
            case UNKNOWN_ERROR ->
                    LOG.error("business error code={} {}", error.code(), where(request));
            default -> LOG.debug("business error code={} {}", error.code(), where(request));
        }
        return response(status.value(), error.code().name());
    }

    @ExceptionHandler(Exception.class)
    // 알려진 웹 오류는 입력 오류로 처리하고 그 외 실패는 상세 내용 없이 UNKNOWN_ERROR로 반환한다.
    public ResponseEntity<Result> unexpected(Exception error, HttpServletRequest request) {
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
            LOG.debug("client error status={} code={} {}", status, code, where(request));
            return response(status, code);
        }
        // 본문 파싱 오류는 일부 Spring 예외에서 ErrorResponse를 구현하지 않는다.
        if (error instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            LOG.debug("client error status=400 code=INVALID_REQUEST {}", where(request));
            return response(400, "INVALID_REQUEST");
        }
        // 예외 객체를 로거에 넘기면 메시지와 스택이 출력되므로 안전한 설명 문자열만 남긴다.
        LOG.error("unexpected error {} {}", where(request), ErrorDescriptions.describe(error));
        return response(500, "UNKNOWN_ERROR");
    }

    // 메서드와 경로만 남긴다. 쿼리스트링은 제외하고 제어 문자·공백은 치환해 로그 주입을 막는다.
    private static String where(HttpServletRequest request) {
        String path = request.getRequestURI();
        String safe = path == null ? "" : path.replaceAll("[^\\x21-\\x7E]", "_");
        if (safe.length() > 200) {
            safe = safe.substring(0, 200);
        }
        return "method=" + request.getMethod().replaceAll("[^A-Za-z]", "_") + " path=" + safe;
    }

    // HTTP 상태와 본문의 resultCode를 일치시키고 JSON 응답 타입을 명시한다.
    private static ResponseEntity<Result> response(int status, String code) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Result(status, code));
    }
}
