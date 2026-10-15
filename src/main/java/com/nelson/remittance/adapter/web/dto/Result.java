package com.nelson.remittance.adapter.web.dto;

// 성공·실패의 공통 상태 코드와 메시지 형식이다.
public record Result(int resultCode, String resultMsg) {
    public static Result ok() {
        return new Result(200, "OK");
    }
}
