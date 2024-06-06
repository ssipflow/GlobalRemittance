package com.moin.globalRemittance.repository;

import com.moin.globalRemittance.entities.Remittance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IRemittanceRepository extends JpaRepository<Remittance, Long> {
}
