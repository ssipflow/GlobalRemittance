# PostgreSQL JDBC 소켓 타임아웃 도입

- 배경: `PostgresFailureModesTest`의 pause(DB 프로세스 동결) 시나리오에서, 풀에서 이미 꺼낸 연결의 요청이 connection-timeout(2초)과 무관하게
  DB가 돌아올 때까지 매달렸다(6초 관찰 후 unpause로 완료). 새 연결이 필요한 요청만 2초에 503으로 끝났다. 요청 스레드와 풀 연결이 무한정 점유될 수 있는 결함이다.
- 선택: postgres 프로파일에 `hikari.data-source-properties.socketTimeout`을 `remittance.db-socket-timeout-seconds`(환경변수
  `REMITTANCE_DB_SOCKET_TIMEOUT_SECONDS`, 기본 60초)로 건다. 응답이 이 시간 동안 없으면 pgjdbc가 연결을 끊고 `PSQLException(08006) <- SocketTimeoutException`을 던진다.
  기존 `DatabaseUnavailability`가 08xxx를 연결 장애로 판별하므로 매핑 변경 없이 503 DB_UNAVAILABLE(`Retry-After: 1`)이 되고, 끊긴 연결은 풀에서 폐기된다.
  테스트에서 실제 적용을 관찰했다(소켓 타임아웃 4초 설정 시 요청 4031ms에 503). H2 프로파일은 이 JDBC 속성을 읽지 않아 런타임은 불변이다. 다만 기동 시 값 검증은 프로파일과 무관하게 적용되므로 H2에서도 잘못된 값이나 잠금 대기 이하의 값은 기동이 거부된다.
- 제약: 값은 락 대기 한도(`lock-timeout-ms`, 최대 30초)보다 커야 한다. 작으면 정상적인 락 대기와 긴 쿼리가 장애로 오인돼 끊긴다.
  기본 60초는 이 최대값의 2배다. 이 관계와 값 형식은 `SocketTimeoutEnvironmentPostProcessor`가 DB 연결 전에 검증한다: 선행 0·부호·소수·단위 없는 1~4자리 정수, 1~3600초,
  그리고 초*1000이 `remittance.lock-timeout-ms`보다 커야 한다(위반 종류를 안내에 구분해 출력). 잠금 대기 값이 잘못됐으면 처리기 실행 순서와 무관하게 비교를 건너뛰고 기존 잠금 대기 검증기가 거부한다.
- 커밋 불확실성: 커밋 요청을 보낸 뒤 응답을 기다리다 소켓 타임아웃으로 끊기면 서버가 커밋했는지 클라이언트는 알 수 없다. 앱은 503을 반환하지만 DB에는 접수가 남았을 수 있다.
  그래서 클라이언트는 같은 `Idempotency-Key`로 재시도해야 한다(이미 커밋됐다면 같은 응답을 재현, 아니면 새로 접수). 이 경우를 결정적으로 재현해 검증하지는 않았다.
  테스트는 쿼리 전송 중 끊김(롤백됨)과 같은 키 재시도가 정확히 1건 접수됨만 확인한다.
- 대안:
  - `statement_timeout`(init SQL): 서버가 쿼리를 취소하는 방식이라 DB 프로세스가 동결된 경우에는 동작하지 않아 무응답에는 무효다. 느린 쿼리 제한은 별도 과제다.
  - `tcpKeepAlive`: 반쯤 열린 연결 감지용이며 OS 기본 주기가 길어 단독으로는 상한을 보장하지 못한다.
  - 애플리케이션 레벨 타임아웃(`@Transactional(timeout)`): 스레드가 소켓 읽기에서 막혀 있으면 중단되지 않아 보조 수단에 그친다.
- 한계: 소켓 타임아웃은 연결 단위 읽기 대기라 쿼리가 계속 일부 바이트를 받는 경우의 전체 시간은 제한하지 못한다. 최악 요청 시간은 약 소켓 타임아웃(+연결 획득 2초 등)이다.
  pause는 실제 네트워크 단절(패킷 드롭)과 다르다. 패킷을 조용히 버리는 블랙홀은 Toxiproxy로 확인했고 같은 경로(소켓 타임아웃 → 503)를 탄다([기록](../harness/tasks/network-partition.md)).

- 운영 참고: Flyway 마이그레이션도 같은 DataSource(소켓 타임아웃 적용)를 쓴다. 현재 V1~V3는 작지만, 큰 테이블에 오래 걸리는 마이그레이션(예: V3의 users 일괄 UPDATE)을 실행할 때는 `REMITTANCE_DB_SOCKET_TIMEOUT_SECONDS`를 충분히 올려 실행한다.
- 4031ms 관찰은 Hikari의 유휴 연결 생존 확인(500ms 초과 유휴 시)과 경합할 수 있어 테스트는 하한을 1.5초로 느슨하게 두고 상한(12초) 안에서 끝나는지만 단언한다.
