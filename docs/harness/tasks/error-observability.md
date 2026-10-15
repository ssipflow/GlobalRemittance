# error-observability

- 배경: 백로그 1번. `src/main`에 로깅이 전혀 없다. 예상 밖 예외(500)는 `ApiExceptionHandler`가 `UNKNOWN_ERROR` 응답만 만들고 흔적을 남기지 않는다.
  DB 제약 오류 로그는 userId 노출 위험으로 `SqlExceptionHelper`를 껐다. 이번 세션에서 DB 비밀번호 불일치가 `Unable to determine Dialect...`로만 보이는
  사례를 겪었다(Hibernate 6.5.2가 원래 SQL 예외를 NPE로 덮어씀).
- 사용자 결정: 1번 진행. 세부 계약은 아래 기본안으로 정했고 PR 본문에 밝힌다.
- 계약:
  1. 요청 ID: 모든 응답(401·404 포함)에 `X-Request-Id` 헤더. 클라이언트가 보낸 값은 `[A-Za-z0-9._-]{1,64}`일 때만 채택, 아니면 UUID를 새로 만든다(로그 주입 방지).
     MDC `requestId`로 로그 패턴에 넣고 요청이 끝나면 반드시 제거한다. 오류 JSON 본문은 바꾸지 않는다(기존 계약 유지).
  2. 안전한 오류 로그: 예상 밖 예외는 ERROR로, 요청 ID·HTTP 메서드·경로(쿼리스트링 제외)·예외 클래스 연쇄·SQLState/벤더 코드·애플리케이션 스택 프레임 일부만 남긴다.
     예외 메시지는 개인정보가 섞일 수 있어 남기지 않는다. LOCK_TIMEOUT은 WARN, 그 밖의 업무 오류는 DEBUG(코드만).
  3. DB 접속 실패 진단: `Unable to determine Dialect without JDBC metadata` 연쇄를 감지하는 FailureAnalyzer가 개인정보 없는 설명과 조치
     (DB 비밀번호·URL 확인, local 프로파일의 Hikari 로그 확인, data/ 디렉터리 정리)를 출력한다.
- 불변식: 로그와 응답 어디에도 예외 메시지, 요청 본문, 쿼리스트링, 이메일·비밀번호·토큰·키가 나오지 않는다. 기존 API·오류 계약은 바뀌지 않는다.
- 완료 기준: 요청 ID 헤더(정상·오류·401·404)·형식 검증·MDC 정리, 500 로그에 요청 ID·예외 클래스가 있고 메시지·본문·쿼리·토큰이 없음을 로그 캡처로 검증,
  업무 오류 코드 로그 수준, FailureAnalyzer 감지, 기존 테스트 전부 통과, Java 17 check·bootJar, 실제 기동으로 DB 접속 실패 출력 확인, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과와 직접 확인
- RequestIdFilter(최우선 순서, 허용 문자 검증, finally에서 MDC 제거), ErrorDescriptions(클래스·SQLState·벤더 코드·우리 코드 프레임 5개, 메시지/toString 미사용),
  ApiExceptionHandler 로깅(예외 객체를 로거에 넘기지 않음, 경로는 안전 문자로 치환·200자 제한), DatabaseConnectionFailureAnalyzer + spring.factories,
  logging.pattern.level에 requestId. ObservabilityTest 8개.
- 실제 기동: 틀린 DB 비밀번호 + 기본 프로파일 → `APPLICATION FAILED TO START`에 Description/Action 출력, 로그에 비밀번호·JDBC URL 0건.
  정상 기동 → 404·401 응답에 `X-Request-Id`, 클라이언트 값(`trace-abc.123`) 유지, 잘못된 값은 UUID로 교체, 로그에 요청 ID와 오류 코드가 연결, 이메일·비밀번호 0건.
- 계약 선택: 요청 ID는 헤더 전용(오류 JSON 본문 불변). 400 계열 업무 오류는 DEBUG라 기본 수준(INFO)에서는 로그에 나오지 않는다.
- 한계: FailureAnalyzer는 Hibernate의 `Unable to determine Dialect...` 문구를 표지로 감지(버전이 바뀌면 재확인). 기본 설정에서는 DB 접속 실패의 실제 원인
  (`Wrong user name or password`)을 로그로 보지 못하고 안내만 받는다. 서블릿 `sendError` 경로(JsonErrorController)의 5xx는 로그를 남기지 않는다.
  실제 500을 일으키는 운영 시나리오는 테스트의 가짜 컨트롤러로만 검증했다.
