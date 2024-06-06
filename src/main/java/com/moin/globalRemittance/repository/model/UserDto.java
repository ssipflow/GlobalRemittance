package com.moin.globalRemittance.repository.model;

import com.moin.globalRemittance.dto.IUserDto;
import com.moin.globalRemittance.entities.User;
import lombok.*;

import java.time.Instant;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class UserDto implements IUserDto {

    private Integer id;
    private String userId;
    private String password;
    private String name;
    private String idType;
    private String idValue;
    private Instant updatedAt;
    private Instant createdAt;


    public User toEntity() {
        User entity = new User();
        entity.setUserId(this.userId);
        entity.setPassword(this.password);
        entity.setName(this.name);
        entity.setIdType(this.idType);
        entity.setIdValue(this.idValue);
        entity.setUpdatedAt(this.updatedAt);
        entity.setCreatedAt(this.createdAt);

        return entity;
    }
}
