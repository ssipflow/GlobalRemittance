# postgres-failure-modes

- 배경: 백로그 "운영 DB 검증 잔여"의 네트워크 분리(무응답)·비정상 종료. 앞선 `postgres-restart-load`는 정상 종료(SIGTERM) 재시작만 다뤘다.
  이 작업은 SIGKILL(`docker kill`)과 pause(프로세스 동결, 연결은 살아 있고 응답만 없음)에서의 앱 동작을 실제 HTTP로 관찰·검증한다.
- 계약(검증할 보장):
  1. 비정상 종료 중·직후 요청은 500 없이 200 또는 `Retry-After: 1`의 503 `DB_UNAVAILABLE`뿐이다(종료 중 접수·이력은 503이어야 한다).
  2. DB를 다시 시작하면 앱이 설정 변경·재기동 없이 스스로 복구한다(상한 90초 폴링).
  3. 실패한 접수는 부분 상태(requested_time/키 불일치 행)를 남기지 않고, kill 전 성공 접수는 보존되며 같은 키 재요청은 같은 응답을 재현한다.
  4. pause 중 새 연결이 필요한 요청은 connection-timeout(2초) 근처에서 503 `DB_UNAVAILABLE`로 끝난다.
  5. pause 중 풀에 연결이 있는 요청도 소켓 타임아웃(테스트 4초) 안에 503 DB_UNAVAILABLE로 끝난다(무한 대기 금지).
  6. pause 해제 뒤 앱은 복구하고 풀 연결이 회수되며(active 0), 접수는 키마다 한 행이고 부분 상태가 없다.
- 불변식: main 코드·설정·API·오류 계약 불변. 기본(H2) 테스트 불변. 태그 `postgres`로만 실행한다.
- 구현 방식: `PostgresRestartLoadTest`와 같은 구조(호스트 포트 고정, 풀 3, connection-timeout 2000, 앱 1개). 고정 sleep 대신 상한 있는 폴링,
  HTTP 연결 5초·요청 20초(무응답 시나리오 40초) 상한, finally에서 `ensureRunning`(unpause/start)으로 컨테이너를 반드시 복구한다.
- 완료 기준: `postgresTest` 반복 통과(`--rerun-tasks`), `spotlessApply check` 통과.

## 구현 결과와 직접 확인 (실제 관찰값, macOS Docker, postgres:16-alpine)
- 구현: `PostgresFailureModesTest`(태그 `postgres`, 2개).
- A. 비정상 종료(kill):
  - kill 중 접수 3회 응답: `503/31ms`(끊긴 기존 연결의 I/O 오류), `503/2018ms`, `503/2013ms`(새 연결 거부 후 connection-timeout). 이력 조회도 503. 500 없음.
  - `start` 명령 후 첫 정상 응답(이력+접수 200)까지 약 4.2초(kill 시점부터 약 10.5초, 3회 반복 중 한 로그 기준). WAL 복구 시간은 이 값에 포함된 관찰값이며 데이터가 작아 짧다.
  - 복구 뒤 kill 전 접수는 보존, 같은 키 재요청은 동일 응답, 접수 2건, 부분 상태 행 0, 같은 키 1행, 새 사용자 흐름 정상.
- B. 무응답(pause):
  - (1) 새 연결이 필요한 요청(풀 `softEvictConnections` 후): 503 `DB_UNAVAILABLE`, 경과 2013ms(connection-timeout에서 종료).
  - (2) [수정 전 관찰] 풀에 연결이 이미 있는 요청: **6초가 지나도 끝나지 않았다**(connection-timeout 2초와 무관). unpause 직후 200 OK로 완료(총 약 6.0초). 즉 쿼리 구간에는 상한이 없다.
    이 테스트의 6초는 관찰 창일 뿐 최대 대기 시간이 아니다. 무응답이 계속되면 요청 스레드·풀 연결을 DB가 돌아오거나 TCP가 끊길 때까지 점유한다(상한은 확인하지 않았다).
  - unpause 후 복구 폴링 성공, 풀 active 0, 접수 3건(키마다 1행), 부분 상태 0.
- 발견된 결함(수정 전): JDBC `socketTimeout`·`statement_timeout`이 없어 DB가 연결은 유지한 채 멈추면 풀에서 꺼낸 연결의 요청이 무한정 매달렸다. 테스트는 처음에 이 동작을 고정했으나 아래 수정으로 기대 동작 단언으로 교체했다.
- 결함 수정(Coordinator 결정으로 같은 브랜치에서 수정, `docs/decisions/db-socket-timeout.md`): postgres 프로파일에 `socketTimeout`을
  `remittance.db-socket-timeout-seconds`(환경변수 `REMITTANCE_DB_SOCKET_TIMEOUT_SECONDS`, 기본 60초)로 도입했다. 락 대기 한도(최대 30초)보다 커야 하며, 이 규칙은 `SocketTimeoutEnvironmentPostProcessor`가 기동 시 검증한다(`SocketTimeoutSettingsTest`로 형식·범위·락 대기 이하·속성 없음·기본 기동 확인).
  테스트는 소켓 타임아웃 4초·lock-timeout 1초로 앱을 띄우고 기대 동작을 단언한다: pause 중 풀 연결 요청이 503 DB_UNAVAILABLE(Retry-After 1)로 끝난다.
- 수정 확인(실제 관찰값): pause (2) 풀 연결 요청이 **503 DB_UNAVAILABLE, 4031ms**(이전: 6초 넘게 미완료 후 unpause로 200). 원인 연쇄는
  `DataAccessResourceFailureException <- JDBCConnectionException <- PSQLException(sqlState=08006) <- SocketTimeoutException`이며 기존 `DatabaseUnavailability`(08xxx)에 그대로 걸려 매핑 수정이 필요 없었다.
  (1) 새 연결 요청은 여전히 2014ms 503. unpause 후 복구 폴링 성공, 풀 active 0, 타임아웃으로 끊긴 요청은 롤백되어 같은 키 재시도가 정확히 1건 접수, 접수 3건·키마다 1행·부분 상태 0.
  `./gradlew postgresTest --rerun-tasks` 반복 통과 여부는 최종 보고 참조.
- 한계: pause는 DB 프로세스 동결이며 실제 네트워크 단절(패킷 드롭/RST/지연)과 다르다. 접수 중 정확한 커밋 시점의 kill(응답 유실 불확실 경우)은 결정적으로 재현하지 못했다.
  소켓 타임아웃 도입 후에도 커밋 응답 대기 중 끊기는 불확실 경우는 재현·검증하지 못했다(같은 Idempotency-Key 재시도로 해결해야 한다고 문서화). 장시간 소크(k6/soak.js)는 이 테스트 범위 밖이다. 단일 앱 인스턴스, postgres:16만 확인했다.

## 2시간 k6 소크 (실제 관찰값, 로컬 1회)
- 조건: 로컬 macOS, 임시 postgres:16-alpine 컨테이너 + `bootRun` 단일 앱 인스턴스, `k6/soak.js` 20 VU, 2시간(2026-10-19 22:51~00:51 KST). 사용자 think time이 있는 접수 흐름이다.
- k6 결과: 요청 895,364건(약 124/s), 반복 385,485회, 임계값 전부 통과. `unexpected_responses` 0, `invariant_violations` 0, `server_error_rate` 0%, `http_req_failed` 0%, `flow_healthy_rate` 100%.
  정상 거절(`expected_rejections`) 54,581건은 계약상 응답이다.
- 지연: 전체 평균 9.5ms, p95 10.4ms. 구간별 p95는 early·mid·late 모두 약 9~11ms로 시간에 따른 악화가 보이지 않았다.
  단발 최대값은 5.75초(구간별 최대 0.3~2.3초대 포함)로 존재했다. 원인(GC·체크포인트·로컬 자원 경합 등)은 분석하지 않았으며 p95 임계값에는 영향이 없었다.
- 자원(1분 샘플 120개): 앱 RSS 141~251MB(초반 평균 164MB, 마지막 30분 평균 157MB로 증가 추세 없음), 스레드 47~58개로 평탄, 힙 사용 약 36~73MB 범위에서 오르내림(초반 평균 50MB, 후반 56MB로 약간 올랐으나 GC 톱니 범위 안이며 누수를 배제한 것은 아님),
  PostgreSQL 연결 수 11 고정(풀 크기 기본값 10 + 샘플러 연결 1로 추정, 증가 없음). DB 크기는 7.9MB → 133MB로 거의 선형 증가(약 1MB/분, 접수·견적 행 누적이므로 정상이며 보존·정리 정책은 별도 과제다).
- 정리 확인: 소크 종료 후 임시 컨테이너(`k6-soak-pg`)와 앱 프로세스가 남아 있지 않다.
- 한계: 2시간·단일 로컬 인스턴스·20 VU다. 수 일 단위 소크, 운영 규모 부하, 실제 네트워크 분리, 접수 커밋 시점 응답 유실은 검증하지 않았다. 운영 보장이 아니다.
