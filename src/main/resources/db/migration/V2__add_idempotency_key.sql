-- 접수 요청의 선택적 Idempotency-Key를 접수된 견적 행에 묶는다. 키가 없는 행은 NULL이다.
-- 이미 ddl-auto로 이 컬럼과 제약이 생긴 DB에서도 실패하지 않도록 IF NOT EXISTS를 쓴다.
ALTER TABLE remittances ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

-- NULL은 유일 제약에서 서로 충돌하지 않으므로 기존 행이 있어도 제약을 추가할 수 있다.
ALTER TABLE remittances
    ADD CONSTRAINT IF NOT EXISTS uk_remittances_user_idempotency_key
    UNIQUE (user_id, idempotency_key);
