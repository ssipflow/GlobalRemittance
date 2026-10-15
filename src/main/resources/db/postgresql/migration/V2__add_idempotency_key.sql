-- 접수 요청의 선택적 Idempotency-Key를 접수된 견적 행에 묶는다. 키가 없는 행은 NULL이다.
-- 새 DB 전용이라 존재 여부 검사가 필요 없고 PostgreSQL은 ADD CONSTRAINT IF NOT EXISTS도 지원하지 않는다.
ALTER TABLE remittances ADD COLUMN idempotency_key VARCHAR(64);

-- NULL은 유일 제약에서 서로 충돌하지 않으므로 기존 행이 있어도 제약을 추가할 수 있다.
ALTER TABLE remittances
    ADD CONSTRAINT uk_remittances_user_idempotency_key
    UNIQUE (user_id, idempotency_key);
