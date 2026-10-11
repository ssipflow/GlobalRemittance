package com.nelson.remittance.adapter.web;

import com.nelson.remittance.adapter.web.ApiModels.Login;
import com.nelson.remittance.adapter.web.ApiModels.LoginResult;
import com.nelson.remittance.adapter.web.ApiModels.Result;
import com.nelson.remittance.adapter.web.ApiModels.Signup;
import com.nelson.remittance.application.UserUseCases;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
// 가입·로그인의 JSON 계약을 사용 사례에 연결하고 민감한 입력을 응답에 재노출하지 않는다.
public class UserController {
    private final UserUseCases users;

    public UserController(UserUseCases users) {
        this.users = users;
    }

    @PostMapping(value = "/signup", consumes = "application/json", produces = "application/json")
    // 검증된 가입 입력을 사용 사례에 전달하고 공통 성공 응답만 반환한다.
    public Result signup(@Valid @RequestBody Signup input) {
        users.signup(
                input.userId(), input.password(), input.name(), input.idType(), input.idValue());
        return Result.ok();
    }

    @PostMapping(value = "/login", consumes = "application/json", produces = "application/json")
    // 검증된 로그인 입력으로 받은 토큰을 API 응답에 담는다.
    public LoginResult login(@Valid @RequestBody Login input) {
        return new LoginResult(200, "OK", users.login(input.userId(), input.password()));
    }
}
