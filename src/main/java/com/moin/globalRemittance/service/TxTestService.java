package com.moin.globalRemittance.service;

import com.moin.globalRemittance.repository.custom.TxTestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TxTestService {

    private final TxTestRepository repository;

    @Transactional
    public void testTransaction1() {
        Integer id = repository.txRequiresNewTest1();
        try {
            repository.txRequiresNewTest2(id);
        } catch(RuntimeException e) {
            throw new RuntimeException(id.toString());
        }
    }

    @Transactional
    public void testTransaction2() {
        Integer id = repository.txRequiredTest1();
        try {
            repository.txRequiredTest2(id);
        } catch(RuntimeException e) {
            throw new RuntimeException(id.toString());
        }
    }
}
