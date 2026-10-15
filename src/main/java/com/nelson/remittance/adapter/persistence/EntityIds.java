package com.nelson.remittance.adapter.persistence;

import static com.nelson.remittance.domain.BusinessException.Code.INVALID_REQUEST;

import com.nelson.remittance.domain.BusinessException;

final class EntityIds {
    private EntityIds() {}

    // 외부 long 식별자가 DB INT 범위를 넘거나 양수가 아니면 변환 전에 거부한다.
    static int toInt(long id) {
        if (id <= 0 || id > Integer.MAX_VALUE) {
            throw new BusinessException(INVALID_REQUEST);
        }
        return (int) id;
    }
}
