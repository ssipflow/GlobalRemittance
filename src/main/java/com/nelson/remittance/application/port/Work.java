package com.nelson.remittance.application.port;

import java.util.function.Supplier;

// 트랜잭션 구현을 내부 정책에서 분리한다. 요청 처리의 사용자 잠금도 같은 트랜잭션에 속한다.
public interface Work {
    <T> T required(Supplier<T> action);
}
