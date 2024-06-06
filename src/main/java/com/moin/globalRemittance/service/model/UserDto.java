package com.moin.globalRemittance.service.model;

import com.moin.globalRemittance.dto.IUserDto;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@ToString
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class UserDto implements IUserDto {

    private Integer id;
    private String userId;
    private String password;
    private String name;
    private String idType;
    private String idValue;
    private Instant updatedAt;
    private Instant createdAt;

}
