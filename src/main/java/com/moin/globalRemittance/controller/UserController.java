package com.moin.globalRemittance.controller;

import com.moin.globalRemittance.controller.model.LoginDto;
import com.moin.globalRemittance.controller.model.UserDto;
import com.moin.globalRemittance.controller.model.response.BaseResponse;
import com.moin.globalRemittance.controller.model.response.LoginResponse;
import com.moin.globalRemittance.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    @PostMapping("/signup")
    public ResponseEntity<BaseResponse> createUser(@Valid @RequestBody UserDto param) {
        userService.createUser(param);
        return ResponseEntity.ok(BaseResponse.builder()
                .resultCode(200)
                .resultMsg("OK")
                .build());
    }

    @PostMapping("/login")
    public ResponseEntity<BaseResponse> login(@Valid @RequestBody LoginDto param) {
        String jwt = userService.login(param);
        return ResponseEntity.ok(LoginResponse.builder()
                .resultCode(200)
                .resultMsg("OK")
                .token(jwt)
                .build());
    }
}
