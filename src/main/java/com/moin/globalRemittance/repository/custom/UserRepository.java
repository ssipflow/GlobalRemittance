package com.moin.globalRemittance.repository.custom;

import com.moin.globalRemittance.dto.IUserDto;
import com.moin.globalRemittance.entities.QUser;
import com.moin.globalRemittance.entities.User;
import com.moin.globalRemittance.repository.model.UserDto;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class UserRepository {

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    private final QUser user = QUser.user;

    public IUserDto getUserByEmail(String email) {
         return queryFactory
                 .select(Projections.fields(UserDto.class,
                         user.id.as("id"),
                         user.userId.as("userId"),
                         user.password.as("password"),
                         user.name.as("name"),
                         user.idType.as("idType"),
                         user.idValue.as("idValue")
                 ))
                 .from(user)
                 .where(user.userId.eq(email))
                 .fetchOne();
    }

    public IUserDto getUserById(Integer id) {
        return queryFactory
                .select(Projections.fields(UserDto.class,
                        user.id.as("id"),
                        user.userId.as("userId"),
                        user.name.as("name"),
                        user.idType.as("idType")
                ))
                .from(user)
                .where(user.id.eq(id))
                .fetchOne();
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void createUser(IUserDto dto) {
        User user = UserDto.builder()
                .userId(dto.getUserId())
                .password(dto.getPassword())
                .name(dto.getName())
                .idType(dto.getIdType())
                .idValue(dto.getIdValue())
                .updatedAt(Instant.now())
                .createdAt(Instant.now())
                .build()
                .toEntity();

        entityManager.persist(user);
    }
}
