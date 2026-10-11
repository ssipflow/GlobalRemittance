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
public class UserController {
    private final UserUseCases users;

    public UserController(UserUseCases users) {
        this.users = users;
    }

    @PostMapping(value = "/signup", consumes = "application/json", produces = "application/json")
    public Result signup(@Valid @RequestBody Signup input) {
        users.signup(
                input.userId(), input.password(), input.name(), input.idType(), input.idValue());
        return Result.ok();
    }

    @PostMapping(value = "/login", consumes = "application/json", produces = "application/json")
    public LoginResult login(@Valid @RequestBody Login input) {
        return new LoginResult(200, "OK", users.login(input.userId(), input.password()));
    }
}
