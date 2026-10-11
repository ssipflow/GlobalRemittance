package com.nelson.remittance.adapter.persistence;

import com.nelson.remittance.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_user_id", columnNames = "user_id"))
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;

    @Column(name = "user_id", nullable = false, length = 255)
    String userId;

    @Column(nullable = false, length = 255)
    String password;

    @Column(nullable = false, length = 128)
    String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_type", nullable = false, length = 16)
    User.IdentityType idType;

    @Column(name = "id_value", nullable = false, length = 128)
    String idValue;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected UserEntity() {}

    UserEntity(User user) {
        userId = user.userId();
        password = user.passwordHash();
        name = user.name();
        idType = user.idType();
        idValue = user.encryptedIdentity();
        createdAt = user.createdAt();
        updatedAt = user.updatedAt();
    }

    public Integer getId() {
        return id;
    }

    User toDomain() {
        return new User(id, userId, password, name, idType, idValue, createdAt, updatedAt);
    }
}
