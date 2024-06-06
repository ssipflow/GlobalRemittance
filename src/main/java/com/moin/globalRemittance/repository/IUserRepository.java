package com.moin.globalRemittance.repository;

import com.moin.globalRemittance.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IUserRepository extends JpaRepository<User, Long> {
}
