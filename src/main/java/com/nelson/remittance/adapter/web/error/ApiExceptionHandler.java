package com.nelson.remittance.adapter.web.error;

import com.nelson.remittance.adapter.web.dto.Result;
import com.nelson.remittance.domain.exception.BusinessException;
import com.nelson.remittance.domain.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
                    case LOCK_TIMEOUT, RATE_UNAVAILABLE, RATE_STALE ->
                            HttpStatus.SERVICE_UNAVAILABLE;
                    case UNKNOWN_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
                    default -> HttpStatus.BAD_REQUEST;
                };
        // 요청 ID는 MDC로 로그 패턴에 붙는다. 업무 오류는 코드만 남기고 예외 메시지·객체는 남기지 않는다.
        switch (error.code()) {
            case LOCK_TIMEOUT, RATE_UNAVAILABLE, RATE_STALE ->
                    LOG.warn("business error code={} {}", error.code(), where(request));
            case UNKNOWN_ERROR ->
                    LOG.error("business error code={} {}", error.code(), where(request));
            default -> LOG.debug("business error code={} {}", error.code(), where(request));
        }
        ResponseEntity<Result> body = response(status.value(), error.code().name());
        if (error.code() == ErrorCode.LOCK_TIMEOUT) {
            // 잠금은 보유 트랜잭션이 끝나면 풀리므로 짧은 재시도가 의미 있다. 초 단위 정수 고정값이다.
            return ResponseEntity.status(body.getStatusCode())
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.getBody());
        }
        return body;
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
        // DB 연결이 끊긴 직후의 실패는 서버 결함이 아니라 재시도 가능한 일시 장애이므로 500 대신 503으로 알린다.
        // 연결이 죽은 트랜잭션은 DB가 롤백하므로 같은 Idempotency-Key로 재시도해도 접수는 한 번만 반영된다.
        // 단 커밋 도중 끊겨 결과가 불확실한 경우도 같은 503이므로 클라이언트는 키로 재시도해 확인해야 한다.
        if (DatabaseUnavailability.isUnavailable(error)) {
            LOG.warn(
                    "database unavailable {} {}",
                    where(request),
                    ErrorDescriptions.describe(error));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Result(503, "DB_UNAVAILABLE"));
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
