package com.nelson.remittance.adapter.web.dto;

// 로그인 성공 시 인증 토큰을 함께 반환한다.
public record LoginResult(int resultCode, String resultMsg, String token) {}
