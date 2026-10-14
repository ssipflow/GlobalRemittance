# postgres-profile

- 배경: 백로그 "운영 DB의 잠금·장애 복구 검증"의 첫 단계. 지금까지 모든 DB 검증이 embedded H2 범위였고, 핵심 주장(사용자 행 잠금으로 동시 접수 직렬화)을
  실제 DB로 확인하지 못했다. 이 작업은 검증의 토대인 PostgreSQL 실행 경로를 만든다. 동시성·잠금·장애 복구 검증은 다음 PR이다.
- 사용자 결정: 1번 진행. 방향은 "기본 실행은 H2 그대로, PostgreSQL은 별도 프로파일과 통합 테스트"(내가 제안, 사용자가 수락).
- 계약:
  1. 기본 프로파일·기존 테스트·`./data` 파일 DB·H2용 `db/migration`은 바뀌지 않는다.
  2. `postgres` 프로파일: PostgreSQL URL/사용자는 환경변수(기본 localhost), 비밀번호는 기존 `REMITTANCE_DB_PASSWORD`. Flyway 위치는 `db/postgresql/migration`
     (H2용 폴더의 하위가 아닌 형제 폴더. Flyway가 하위까지 스캔하기 때문), 기준선 끔.
  3. PostgreSQL V1/V2는 H2와 같은 스키마를 PostgreSQL 문법으로 작성한다. H2 `ENUM`은 `VARCHAR + CHECK`, `IF NOT EXISTS`는 쓰지 않는다(새 DB 전용).
  4. 잠금 대기 한도는 H2의 URL `LOCK_TIMEOUT` 대신 풀의 `connection-init-sql`(`SET lock_timeout`)로 적용한다. 값은 기동 시 검증된 1~30000 정수다.
  5. PostgreSQL 테스트는 JUnit 태그 `postgres`이고 `postgresTest` 태스크로만 실행한다. 기본 `check`는 Docker 없이 통과해야 한다. Docker가 없으면 건너뛰지 않고 실패한다.
- 불변식: 기본 실행·API·오류 계약 불변. 기본 테스트 개수 97개 유지.
- 완료 기준: postgresTest 통과(스키마·validate·lock_timeout·API 스모크), 기본 check·bootJar 통과, 실제 PostgreSQL 컨테이너에 앱을 띄운 수동 확인, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.
- 다음 PR: 동시 접수·한도·중복·멱등성·락 타임아웃(55P03)·롤백/장애 복구를 PostgreSQL에서 검증.

## 구현 결과와 직접 확인
- 구현: `application-postgres.yaml`, PostgreSQL 전용 V1/V2, build.gradle(의존성·`excludeTags`·`postgresTest`), PostgresProfileTest(태그 `postgres`).
- 수동 확인(스크래치 작업 디렉터리, 임시 컨테이너 postgres:16-alpine): PostgreSQL 16.15에서 V1·V2 적용, 가입→로그인→견적→접수, 같은 키 재요청 200/200, 이력 `todayTransferCount` 1.
  틀린 비밀번호는 Flyway 단계에서 `password authentication failed`로 실패하고 로그에 비밀번호·JDBC URL이 없었다.
- 자동 검증: 기본 `clean check bootJar` 97개 통과(PostgreSQL 태그 테스트 제외, 기존 개수와 동일), `postgresTest` 6개 통과(PostgreSQL 확인·Flyway 이력·lock_timeout·제약·인덱스·ENUM 대응 CHECK·API 스모크), 실패·건너뜀 0.
- 독립 리뷰: 차단 결함 없음. 리뷰 후 CHECK 검증 테스트 추가, 테스트 주석 정정, README docker 예시(127.0.0.1 바인딩·준비 대기·정리), 문서 정정은 재리뷰 없이 `verify`로만 확인했다.
- **발견(이번 작업 범위 밖, 후속)**: Flyway 도입(PR #23) 이후 기본 프로파일의 틀린 H2 비밀번호는 Flyway가 먼저 실패해 `DatabaseConnectionFailureAnalyzer`의 `Dialect` 안내 블록이 나오지 않는다
  (직접 확인: `APPLICATION FAILED` 0건, `Wrong user name or password` 로그에 존재, 비밀번호·URL 0건). 실제 원인은 스택에 남아 관측성은 나쁘지 않으나 안내 블록 기능이 사실상 비활성이다.
  분석기를 Flyway 접속 실패에 맞추는 작업은 이 작업의 소유 파일 밖이라 후속으로 남긴다. README·architecture·design의 해당 설명은 실제 동작에 맞게 고쳤다.
- 한계: `application.yaml`의 lock-timeout 주석은 "H2 URL의 LOCK_TIMEOUT"으로만 적혀 있어 postgres 프로파일의 `SET lock_timeout`을 반영하지 못한다(소유 파일 밖, 다음 PR에서 정리).
  동시성·한도·락 타임아웃(55P03)·롤백/장애 복구는 PostgreSQL에서 아직 검증하지 않았다. PostgreSQL 16 한 버전만 사용했다.
