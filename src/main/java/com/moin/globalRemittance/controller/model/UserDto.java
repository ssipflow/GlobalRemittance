package com.moin.globalRemittance.controller.model;

import com.moin.globalRemittance.dto.IUserDto;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

@Getter
@Setter
@ToString
public class UserDto implements IUserDto {

    private Instant updatedAt;

    private Instant createdAt;

    private Integer id;

    @Email(message = "Invalid email format")
    @NotBlank (message = "userId is mandatory")
    private String userId;

    @NotBlank(message = "password is mandatory")
    private String password;

    @NotBlank(message = "name is mandatory")
    private String name;

    @NotBlank(message = "idType is mandatory")
    @Pattern(regexp = "REG_NO|BUSINESS_NO", message = "idType must be either REG_NO or BUSINESS_NO")
    private String idType;

    @NotBlank(message = "idValue is mandatory")
    private String idValue;

}
