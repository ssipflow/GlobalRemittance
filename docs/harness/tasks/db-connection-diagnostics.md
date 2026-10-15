# db-connection-diagnostics

- 배경: PR #22가 만든 `DatabaseConnectionFailureAnalyzer`는 Hibernate의 `Unable to determine Dialect...` 표지로 DB 접속 실패를 안내했다.
  Flyway 도입(PR #23) 이후 접속 실패가 Flyway 초기화에서 먼저 터져 이 안내 블록이 나오지 않는다. PR #27에서 H2 기본 프로파일로 직접 확인했고(안내 블록 0건,
  스택에는 원인이 남고 비밀번호·URL은 없음) 문서만 정정한 채 후속으로 남겼다.
- 사용자 결정: 권장 순서(1순위)로 진행.
- 계약:
  1. Flyway의 접속 실패(`FlywaySqlException: Unable to obtain connection from database`)를 감지하고, 연쇄의 SQLState만으로 원인을 분류한다.
     `28xxx` 인증 실패, `08xxx` 연결 불가, `3D000` DB 없음, 그 밖은 일반 접속 실패 안내.
  2. 출력에는 비밀번호·사용자명·호스트·URL·예외 메시지를 넣지 않는다(SQLState 코드만). 예외 메시지에 섞일 수 있는 값을 쓰지 않는 것이 핵심 불변식이다.
  3. 기존 Dialect 경로는 유지한다. 접속 실패가 아닌 Flyway 오류(마이그레이션 SQL 실패 등)는 가로채지 않는다.
- 불변식: 기동 실패 시 안내 외에 앱 동작·API·설정은 불변. 기본 테스트는 Docker 없이 통과.
- 완료 기준: 실제 H2 인증 실패와 PostgreSQL(인증/연결 불가/DB 없음)에서 분류 결과와 비노출을 테스트로 확인, 실제 jar 기동에서 `APPLICATION FAILED TO START` 블록 출력 확인
  (H2 기본 프로파일, PostgreSQL), Java 17 check·bootJar·postgresTest, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과와 직접 확인
- 구현: `DatabaseConnectionFailureAnalyzer`가 Flyway 접속 실패(`org.flywaydb.core.internal.exception.FlywaySqlException`, 메시지 "Unable to obtain connection from database")를 감지하고
  연쇄의 SQLException(`getNextException` 포함)에서 SQLState로 분류한다(우선순위 `28*` → `3D000` → `08*` → 그 밖). 접속 실패가 아닌 Flyway 오류와 무관한 예외는 null.
- 테스트: `DatabaseConnectionFailureTest`(기본, 9개: 실제 H2 인증 실패 + 콘솔 블록 구간 비노출, 우선순위(28>08, 3D000>08), 형식 오류/null SQLState, `getNextException`, 원인 순환 종료, 비접속 Flyway 예외 null, DB 종류별 조치, 등록),
  `PostgresConnectionFailureTest`(postgres, 3개: 28P01·08001·3D000). 기본 check 106개(기존 97 + 9), postgresTest 17개(기존 14 + 3), 실패·건너뜀 0.
- 실제 jar 기동(스크래치 작업 디렉터리, 임시 PostgreSQL 컨테이너): H2 기본 프로파일 틀린 비밀번호(28000), PostgreSQL 틀린 비밀번호(28P01)·닫힌 포트(08001)·없는 DB(3D000)에서 안내 블록 출력.
  블록과 로그에 비밀번호 0건, JDBC URL 0건. 컨테이너는 정리했다.
- 리뷰 지적과 정정: 독립 리뷰어가 "안내 아래 스택에 원인이 남는다"는 안내 문구를 의심했고, 실제 로그로 확인해 **거짓임을 확인**했다(블록 아래에는 스택이 없고 원래 메시지는 블록 바로 위 WARN 한 줄). 문구를 정정했다.
  이 오류는 이전 PR(#27)에서 "스택에 원인이 남는다"고 적은 서술이 분석기 도입 후에는 달라진다는 점도 의미한다(블록이 나오면 스택은 억제됨).
  함께 반영: SQLState 형식 검증, 우선순위와 일치하는 SQLState 표시, 예외 클래스명 기반 DB 종류 구분(pg_hba.conf 안내), 합성 단위 테스트, 콘솔 블록 구간 비노출 단언.
- 한계: 안내 블록 밖의 라이브러리 WARN 로그에는 사용자명·호스트:포트가 나온다(바꾸지 않았다). 감지는 Flyway 내부 클래스·영어 메시지 접두사에 의존한다.
  분류는 SQLState에 의존한다. `setNextException` 순환은 Flyway 자체 `getMessage()`가 무한 루프라 테스트하지 못했다(원인 순환만 검증).
