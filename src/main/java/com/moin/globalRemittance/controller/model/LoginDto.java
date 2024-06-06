package com.moin.globalRemittance.controller.model;

import com.moin.globalRemittance.dto.IUserDto;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

@Getter
@Setter
@ToString
public class LoginDto implements IUserDto {

    @Email(message = "Invalid email format")
    @NotBlank (message = "userId is mandatory")
    private String userId;

    @NotBlank(message = "password is mandatory")
    private String password;

    private Instant updatedAt;
    private Instant createdAt;
    private Integer id;
    private String name;
    private String idType;
    private String idValue;
}
