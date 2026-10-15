package com.nelson.remittance.adapter.web.controller;

import com.nelson.remittance.adapter.web.dto.Login;
import com.nelson.remittance.adapter.web.dto.LoginResult;
import com.nelson.remittance.adapter.web.dto.Result;
import com.nelson.remittance.adapter.web.dto.Signup;
import com.nelson.remittance.application.user.UserUseCases;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
@Tag(name = "회원", description = "회원가입과 로그인. 인증 없이 호출한다.")
// 가입·로그인의 JSON 계약을 사용 사례에 연결하고 민감한 입력을 응답에 재노출하지 않는다.
public class UserController {
    private final UserUseCases users;

    public UserController(UserUseCases users) {
        this.users = users;
    }

    @Operation(summary = "회원가입", description = "이메일을 사용자 ID로 가입한다. 비밀번호와 식별번호 원문은 응답에 포함하지 않는다.")
    @ApiResponse(
            responseCode = "400",
            description = "입력 형식 오류(INVALID_REQUEST)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @ApiResponse(
            responseCode = "409",
            description = "이미 가입한 이메일(DUPLICATE_USER)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @PostMapping(value = "/signup", consumes = "application/json", produces = "application/json")
    // 검증된 가입 입력을 사용 사례에 전달하고 공통 성공 응답만 반환한다.
    public Result signup(@Valid @RequestBody Signup input) {
        users.signup(
                input.userId(), input.password(), input.name(), input.idType(), input.idValue());
        return Result.ok();
    }

    @Operation(summary = "로그인", description = "성공하면 30분간 유효한 JWT를 token으로 반환한다.")
    @ApiResponse(
            responseCode = "400",
            description = "입력 형식 오류(INVALID_REQUEST)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @ApiResponse(
            responseCode = "401",
            description = "계정 없음 또는 비밀번호 불일치(UNAUTHORIZED, 둘을 구분하지 않음)",
            content = @Content(schema = @Schema(implementation = Result.class)))
    @PostMapping(value = "/login", consumes = "application/json", produces = "application/json")
    // 검증된 로그인 입력으로 받은 토큰을 API 응답에 담는다.
    public LoginResult login(@Valid @RequestBody Login input) {
        return new LoginResult(200, "OK", users.login(input.userId(), input.password()));
    }
}
