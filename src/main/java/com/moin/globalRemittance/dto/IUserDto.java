package com.moin.globalRemittance.dto;

import java.time.Instant;

public interface IUserDto {
    Integer getId();
    String getUserId();
    String getPassword();
    String getName();
    String getIdType();
    String getIdValue();
    Instant getUpdatedAt();
    Instant getCreatedAt();
    String toString();
}
