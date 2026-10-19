# DB 연결 장애를 503 DB_UNAVAILABLE로 응답

- 배경: PostgreSQL 검증에서 DB가 연결을 끊은 직후 첫 요청이 500(`UNKNOWN_ERROR`)이 됨을 관찰했다. Hikari가 직전 500ms 안에 쓴 연결의 생존 확인을 건너뛰어 끊긴 연결을 요청이 처음 쓰기 때문이다.
- 선택(사용자): 500을 503 + `Retry-After: 1`로 매핑한다. 첫 실패 자체를 없애지는 않는다.
- 구현: 웹 어댑터 `ApiExceptionHandler`가 원인 연쇄의 SQLState(08xxx, 57P01~03)와 JDBC 연결 예외로 판별(`DatabaseUnavailability`). 메시지는 읽지 않는다. 응답 코드는 도메인 `ErrorCode`가 아니라 웹 계층의 문자열 `DB_UNAVAILABLE`(404 NOT_FOUND 등과 같은 층).
- 대안: (a) Hikari `aliveBypassWindowMs=0`: 첫 500은 없어지지만 JVM 전역 시스템 속성이고 모든 대여에 왕복 1회 비용. (b) 서버 내부 재시도: 사용자에게 보이는 실패가 없지만 구현·멱등성 검증 범위가 큼.
- 비용/한계: SQLState 기반이라 다른 DB·드라이버는 재확인 필요. 커밋 도중 끊겨 결과가 불확실한 경우도 같은 503이 될 수 있다(미검증) → 클라이언트는 `Idempotency-Key`로 재시도해야 한다.
