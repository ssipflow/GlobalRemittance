package com.nelson.remittance.adapter.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
// 서블릿 오류 디스패치에서도 HTML 화면 대신 동일한 JSON 오류 경계를 제공한다.
public class JsonErrorController implements ErrorController {
    @RequestMapping("/error")
    // 서블릿 오류 상태만 읽고 원시 메시지나 예외 속성은 응답에 포함하지 않는다.
    public ResponseEntity<ApiModels.Result> error(HttpServletRequest request) {
        // 서블릿 sendError도 HTML 기본 오류 화면으로 빠지지 않도록 JSON 경계를 유지한다.
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer value ? value : 404;
        if (status < 400 || status > 599) {
            status = 500;
        }
        String code =
                switch (status) {
                    case 401 -> "UNAUTHORIZED";
                    case 403 -> "FORBIDDEN";
                    case 404 -> "NOT_FOUND";
                    case 405 -> "METHOD_NOT_ALLOWED";
                    case 406 -> "NOT_ACCEPTABLE";
                    case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                    default -> status >= 500 ? "UNKNOWN_ERROR" : "INVALID_REQUEST";
                };
        // ERROR_MESSAGE/EXCEPTION 등 원시 오류 속성은 개인 정보가 포함될 수 있어 읽지 않는다.
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiModels.Result(status, code));
    }
}
