# local-sql-logging

- 배경: 사용자 요청. 로컬 실행 중 앱이 어떻게 동작하는지 SQL 로그로 보고 싶고, 앞선 DB 비밀번호 사례처럼 DB 오류 원인이 로그에 안 보이는 문제도 줄인다.
- 계약: `local` 프로파일에서만 `org.hibernate.SQL` DEBUG(문장은 `?` 자리표시자)와 `format_sql`을 켜고, 기본 설정이 끈 `SqlExceptionHelper`를 WARN으로 켠다.
  기본 실행(프로파일 없음)은 변하지 않는다: SQL 로그 없음, SqlExceptionHelper OFF.
- 개인정보: 바인딩 값 로그(`org.hibernate.orm.jdbc.bind`)는 이메일·비밀번호 해시·암호문이 남으므로 기본은 주석 처리한 선택지로만 둔다.
  SqlExceptionHelper WARN은 오류 메시지에 userId가 포함될 수 있어 local 전용이며 README에 명시한다.
- 완료 기준: local 프로파일 환경에서 위 설정이 적용되고 기본 환경에서는 적용되지 않는 테스트, 실제 local 기동에서 가입 호출 시 insert SQL이 로그에 나오는지 확인,
  Java 17 check·bootJar, README·overview 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과와 직접 확인
- application-local.yaml: `format_sql: true`, `org.hibernate.SQL: DEBUG`, `SqlExceptionHelper: WARN`, 바인딩 로그는 주석으로만.
- LocalLoggingTest 3개(local 프로파일 2: 환경값·로거 효과·바인딩 로그 꺼짐, 기본 1: SQL 미설정·SqlExceptionHelper OFF).
- 실제 local 기동(메모리 DB): 가입 호출 시 `select users` → `insert users` → 중복 가입 시 `select users`가 줄바꿈된 DEBUG 로그로 출력.
  로그 파일에 가입 이메일은 0건. 중복 가입은 사전 조회로 처리되어 SqlExceptionHelper 경고는 나오지 않았다.
- 알려진 부작용: 로거 레벨은 JVM 전역이라 테스트에서 local 컨텍스트가 뜬 뒤 다른 컨텍스트의 테스트 출력에도 SQL DEBUG가 섞일 수 있다.
  기본 컨텍스트 테스트는 로거가 아니라 환경값만 단언해 오염을 피했다.
- DB 인증 실패 재현(스크래치 파일 DB를 pwA로 만들고 틀린 비밀번호로 local 기동): SqlExceptionHelper WARN만으로는 원인이 로그에 나오지 않았다.
  Hibernate 6.5.2가 부트스트랩에서 `NullPointerException`(sqlExceptionHelper가 null)으로 SQL 예외를 덮어쓴다. 원래 계획의 가정이 틀렸다.
  `com.zaxxer.hikari: DEBUG`를 켜면 `Failed to create/setup connection: Wrong user name or password [28000-224]`가 로그에 나온다. local 프로파일에 반영했다.
- 기본 실행(프로파일 없음)의 로그는 변하지 않는다. 실제 브라우저 확인과 무관한 로그 설정이다.
- Hikari DEBUG 노출 범위 확인(틀린 비밀번호 local 기동 로그): `dataSourceProperties…{password=<masked>`와 `password…<masked>`로 출력되고,
  사용한 비밀번호 문자열과 AES·JWT 키 값은 로그에 0건. JDBC URL(로컬 파일 경로)과 사용자명은 출력된다.
- 리뷰 반영: README의 오류 코드를 `[28000-224]`로 맞추고 Hibernate 원인 서술을 '관찰된다'로 완화, 문단을 항목별로 분리.
- 비차단으로 남김: 바인딩 로그 꺼짐 테스트의 `isTraceEnabled` 단언은 로거 전역 상태에 의존(현재 다른 테스트가 켜지 않음).
