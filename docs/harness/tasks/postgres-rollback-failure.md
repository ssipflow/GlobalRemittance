# postgres-rollback-failure

- 배경: 백로그 후보 2(운영 DB 검증 남은 범위) 중 사용자가 "롤백 실패 주입 (PostgreSQL)"을 선택. 지금까지 롤백 실패는 H2+Mockito 대역으로만 재현했다.
- 계약:
  1. 트랜잭션 도중 DB가 그 연결의 백엔드를 종료(`pg_terminate_backend`)해 실제 JDBC 롤백이 실패하는 상황을 만든다.
  2. `RollbackSafeTransactionManager` 아래에서 호출자는 원래 `BusinessException`을 받고, 같은 트랜잭션의 쓰기는 남지 않으며, 트랜잭션 리소스·풀 점유 연결이 정리되고 이후 풀이 복구된다.
  3. 롤백이 실제로 실패했다는 증거(WARN 로그)를 단언한다. 대조로 기본 `JpaTransactionManager`는 원래 예외를 덮는다.
- 불변식: 운영 코드·API·오류 계약 불변. 기본 check 개수 불변. 테스트는 태그 `postgres`(`postgresTest`)로만 실행.
- 완료 기준: 새 테스트 통과(반복 실행), 기본 check·bootJar·postgresTest 통과, 문서의 "롤백 실패 PostgreSQL 미검증" 서술 갱신, dev PR.
- 종료: 커밋·push·dev PR 후 옵시디언 sync(앱 문서 변경). 승인·병합은 사람.

## 구현 결과와 직접 확인
- 구현: `PostgresRollbackFailureTest`(2개). 가입 쓰기 후 자기 연결을 종료시키고 LOCK_TIMEOUT으로 실패하는 Probe 빈을 테스트 설정으로 등록. 운영 코드 변경 없음.
- 직접 확인: 롤백 실패 WARN은 `DataAccessResourceFailureException`, SQLState `57P01`. 안전 매니저는 원래 `BusinessException`(LOCK_TIMEOUT) 전달, `users` 행 0건, 점유 연결 0, 이후 `select 1` 복구.
  기본 `JpaTransactionManager`는 같은 상황에서 업무 예외를 덮었다. `--rerun-tasks` 3회 모두 통과.
- 한계: 롤백 실패 원인은 연결 종료 한 가지다. 커밋 단계 실패, DB 재시작·네트워크 분리, 서비스 수준(HTTP) 경로의 롤백 실패는 검증하지 않았다.
- 독립 리뷰: 차단 결함 없음. 반영: 대조 테스트를 `DataAccessException`+원인 연쇄 SQLException으로 좁힘, 로그의 `sqlState=57P01` 단언, 풀 복구 확인 후 행 수 확인. 이후 `--rerun-tasks` 추가 2회 통과.
  권고(비차단, 미반영): 컨테이너 공용화(postgres 테스트가 클래스마다 컨테이너 1개), 과거 기록 2곳의 당시 서술은 이력이라 유지.
