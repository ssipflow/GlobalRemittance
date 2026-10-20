# commit-response-loss

- 배경: 앞선 `postgres-failure-modes`가 "커밋 응답이 유실되는 불확실한 경우"를 재현하지 못한 한계로 남겼다.
  DB는 커밋했는데 앱이 결과를 모르는 경우, 클라이언트가 같은 Idempotency-Key로 재시도해도 안전한지 확인한다.
- 계약: 응답 유실 시 500 없이 `Retry-After: 1`의 503 `DB_UNAVAILABLE`이며, 같은 키 재시도는 접수 1건·한도 집계 1회 반영·정상 성공과 같은 200 응답이다.
  커밋이 DB에 닿기 전에 끊기면 롤백되어 0건이고, 재시도가 1건으로 성공한다.
- 구현: `PostgresCommitResponseLossTest`(태그 `postgres`, 2개). 앱 JDBC와 PostgreSQL 사이에 테스트용 TCP 프록시를 두고,
  commit 메시지를 식별해 한 번만 결함을 주입한다(sleep·경쟁 없음, 주입 횟수 단언). main 코드는 변경하지 않았다.
  - pgjdbc는 commit을 simple Query가 아니라 `Parse "COMMIT"` 후 `Bind/Execute/Sync`로 보낸다. 프록시는 Parse된 COMMIT 이름과 simple Query COMMIT을 모두 commit으로 본다.
- 관찰값(3회 반복, 모두 통과):
  - 응답 유실(서버 커밋 후 응답 폐기, 소켓 리셋): 첫 응답 503 `DB_UNAVAILABLE`+`Retry-After: 1`, 약 9~16ms. 원인 연쇄 `DataAccessResourceFailureException <- PSQLException(08006) <- SocketException`.
    DB에는 해당 키 1행이 커밋되어 있고, 같은 키 재시도는 200이며 정상 성공 본문과 동일, 두 번째 재시도도 동일. 행 수·한도 합계는 변하지 않았고 `todayTransferCount`가 DB 집계와 일치. 다른 사용자 요청은 정상.
  - 커밋 미도달(COMMIT 폐기): 503, DB 0행·한도 합계 0, 같은 키 재시도 200으로 1행.
- 결과: 결함 없음. 500은 나오지 않았고 멱등성으로 중복·이중 반영이 막혔다.
- 한계: 프록시로 만든 TCP 리셋이며 실제 패킷 드롭·지연이 아니다. 응답 폐기 지점은 commit 한 곳이다. 요청 본문이 다른 같은 키(422 정책)는 이 작업 범위 밖. 단일 인스턴스·postgres:16만 확인했다.
