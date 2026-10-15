# request-idempotency

- 배경: 백로그 1번. 접수 응답을 받지 못한 클라이언트(타임아웃·503)가 재시도하면 이미 성공했어도 409 ALREADY_REQUESTED를
  받아 성공과 실패를 구분하지 못한다. 이력으로 직접 확인해야 한다.
- 사용자 결정: 멱등성 키 도입(권장안 채택).
- 계약: `POST /transfer/request`의 선택 헤더 `Idempotency-Key`(1~64자, `[A-Za-z0-9._~-]`). 형식 위반·빈 값은 400 INVALID_REQUEST.
  키가 없으면 기존 동작이 그대로다(하위 호환).
  - 같은 사용자·같은 키·같은 견적, 성공한 접수 후 24시간 안: 200 OK를 다시 돌려주고 아무것도 바꾸지 않는다(재현).
  - 같은 사용자·같은 키·다른 견적: 422 IDEMPOTENCY_KEY_REUSED(키는 영구히 그 견적에 묶임).
  - 같은 키·같은 견적이지만 24시간 이후: 기존 규칙대로 409 ALREADY_REQUESTED.
  - 키 없이 이미 접수된 견적에 새 키: 409 ALREADY_REQUESTED.
  - 실패한 시도(LIMIT_EXCESS, QUOTE_EXPRIED, 403, 404, 503 등)는 키를 소비하지 않는다. 재시도하면 그 시점 규칙으로 다시 평가한다.
  - 키 범위는 사용자별이다. 다른 사용자의 같은 키 문자열은 서로 영향이 없다.
- 저장: 접수된 견적 행에 `idempotency_key`(nullable)를 두고 (user_id, idempotency_key) 유일 제약. 별도 키 테이블과 정리 작업은 없다.
- 동시성: 키 조회와 접수는 기존 사용자 행 잠금 안에서 수행해 같은 키의 동시 재시도가 한 번만 반영된다. 유일 제약은 최종 방어다.
- 불변식: 같은 키로 몇 번을 보내도 접수·한도 합계는 한 번만 반영된다. 응답 재현은 성공 응답뿐이다.
- 완료 기준: 재현(200)·다른 견적 거부·다른 사용자 격리·실패 비소비·24시간 경계·형식 오류·키 없는 호환·동시 같은 키 1회
  반영 회귀 검증, 스키마(유일 제약) 검증, Java 17 check·bootJar, docs/application 갱신, backlog 1번 완료 표시.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과
- 도메인: Quote에 idempotencyKey(null 허용) 추가, request(actor, now, key)는 검증을 모두 통과한 접수에만 키를 묶는다.
  새 오류 코드 IDEMPOTENCY_KEY_REUSED(HTTP 422).
- 응용: request(actor, quoteId, key)가 사용자 잠금 → 견적 조회 → (본인 견적이고 키가 있으면) 키 조회 순으로 판단.
  재현 창 24시간은 TransferUseCases 상수. 재현 시 만료·한도를 다시 평가하지 않는다.
- 영속: remittances.idempotency_key(길이 64, nullable)와 uk_remittances_user_idempotency_key(user_id, idempotency_key).
  저장 flush의 23505를 IDEMPOTENCY_KEY_REUSED로 바꾸는 최종 방어.
- 웹: Idempotency-Key 헤더(선택), 정규식 `[A-Za-z0-9._~-]{1,64}`, 위반은 400.
- 결정: 타인 견적에 내 키를 써도 키 판단 없이 403(존재 여부 노출 방지). 키 없이 접수된 견적에 새 키를 붙이면 409.
  같은 키·다른 견적의 동시 요청 테스트는 계약에 없던 추가분이다.

## 내부 리뷰 반영
- 독립 리뷰어: 차단 결함 없음. 제안 중 다음은 문서에 한계로 기록했다: 유일 제약 최종 방어는 직접 테스트하지 않음,
  SQLState 23505는 H2·PostgreSQL 기준, 기존 파일 DB에 ddl-auto update로 컬럼·제약을 추가하는 과정은 미확인.
- 반영하지 않은 제안: 스키마 테스트의 컬럼 순서 의존 개선, 실패 비소비 테스트 분리, 존재하지 않는 견적+키·중복 헤더 직접 테스트.
  모두 비차단이며 후속 개선으로 남긴다.
