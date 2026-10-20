# network-partition

- 배경: 앞선 pause(프로세스 동결)·TCP 프록시 리셋 검증은 실제 네트워크 장애와 달랐다. Toxiproxy(Testcontainers, `ghcr.io/shopify/toxiproxy:2.9.0`)로 앱-PostgreSQL 사이에 실제 장애 형태를 만든다.
- 구현: `PostgresNetworkPartitionTest`(태그 `postgres`, 5개)와 `testImplementation 'org.testcontainers:toxiproxy'`(버전은 Boot BOM의 Testcontainers 1.19.8). main 코드 불변.
  앱은 풀 3·connection-timeout 2초·소켓 타임아웃 4초·락 대기 1초로 Toxiproxy 포트에 접속한다. DB 상태는 컨테이너 직접 JDBC로 단언하고, 각 시나리오는 finally에서 toxic 제거·프록시 활성화로 복구한다.
- 관찰값(3회 반복 모두 통과, 약 ±15ms 동일). 모든 503은 `DB_UNAVAILABLE`+`Retry-After: 1`이었고 500은 없었다:
  | 시나리오 | 결과 |
  | --- | --- |
  | 블랙홀(timeout toxic 0, 양방향), 풀 연결 요청 | 503, 약 4012~4018ms(소켓 타임아웃) |
  | 블랙홀, 새 연결 요청 | 503, 약 2016~2022ms(connection-timeout) |
  | 블랙홀 해제 후 | 약 40ms 내 200, 풀 active 0, 부분 상태 0, 같은 키 재시도 1건 |
  | `reset_peer` | 503 약 7ms, 복구 16~23ms |
  | 프록시 disable/enable | 503 약 22ms, 복구 45~57ms |
  | 지연 1000ms(응답 방향) | 200이지만 약 6050ms(요청 한 건에 지연된 응답 읽기가 여러 번(약 6회로 추정, 읽기 횟수는 측정하지 않음) 쌓이고 개별 읽기는 4초 미만) |
  | 지연 6000ms | 503 약 4015~4026ms, 해제 후 복구 약 24ms |
  | 응답 방향만 블랙홀 | 503 약 4012~4017ms, 복구 후 같은 키 재시도 200·정확히 1행 |
- 결론: 패킷을 조용히 버리는 블랙홀에서도 요청은 소켓 타임아웃/연결 타임아웃 안에 끝나고, 해제되면 설정 변경 없이 복구했다(이 환경에서 약 40ms, 타임아웃으로 끊긴 연결이 풀에서 폐기된 뒤의 관찰값). 지연은 개별 읽기가 소켓 타임아웃보다 짧으면 요청 전체가 소켓 타임아웃보다 길어질 수 있다(읽기 단위 타임아웃의 한계).
- 한계와 주의:
  - 응답 방향 블랙홀 시나리오는 첫 쿼리 응답이 막혀 COMMIT 전송 전에 타임아웃되므로 "커밋됐지만 응답을 못 받음"을 재현하지 못한다(DB 0행). 그 경우는 `PostgresCommitResponseLossTest`가 다룬다. 이 테스트는 반영 행 0건을 단언하고(결정적), 재시도 후 1건을 단언한다.
  - 장애 중 로그의 PSQLException 스택트레이스 WARN은 우리 코드가 아니라 Hikari(`ProxyConnection`: "marked as broken because of SQLSTATE(08006)")가 남긴다. 우리 코드는 클래스·SQLSTATE·경로만 로그한다.
    라이브러리 로그는 `docs/application/design.md`의 로그 범위 밖이라고 이미 적혀 있다. 이 장애(I/O 오류)의 로그에서 개인정보는 확인되지 않았으나, 서버 오류 메시지가 값을 담는 경우는 이 작업에서 시험하지 않았다.
  - Toxiproxy는 로컬 컨테이너 네트워크의 TCP 프록시라 실제 인터넷 구간의 패킷 손실·MTU·방화벽 상태 추적 문제와는 다르다. 단일 인스턴스·postgres:16만 확인했다.
