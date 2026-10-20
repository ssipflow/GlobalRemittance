# Observability

관련 문서: [아키텍처](architecture.md) · [동시성과 멱등성](concurrency.md) · [API](api.md). 현재 구현은 요청 식별·선별 오류 로그·로컬 DB 진단·기동 실패 안내다. 아래 내용은 현재 코드의 설명이며 문서 작성 중 앱 테스트를 새로 실행한 결과가 아니다.

## 요청과 로그 연결

`RequestIdFilter`는 보안 필터보다 먼저 실행한다. `X-Request-Id`가 `[A-Za-z0-9._-]{1,64}`에 맞으면 채택하고, 없거나 잘못됐으면 UUID를 만든다. 값을 응답 헤더와 MDC의 `requestId`에 넣고 `finally`에서 제거한다. 로그 패턴 `%X{requestId:-}`로 같은 요청의 로그를 찾는다. 정상 응답에도 요청 ID가 있지만 모든 요청에 오류 로그가 생기는 것은 아니다.

허용 문자·길이는 개행을 통한 로그 오염을 줄이고, MDC 정리는 스레드 재사용 시 다른 요청의 ID가 남는 것을 막는다. 형식 검사는 개인정보의 의미적 마스킹이나 ID의 신뢰성·전역 유일성을 보장하지 않는다. 비동기 executor와 외부 서비스로 문맥을 전파하는 기능도 없다.

| 식별자 | 용도 |
| --- | --- |
| X-Request-Id | 개별 HTTP 요청과 로그 연결 |
| Idempotency-Key | 여러 HTTP 재시도에서 같은 접수 성공을 재현 |
| Trace ID / Span ID | 현재 계측·수집·전파 미구현 |

## 오류 분류와 진단 정보

`ApiExceptionHandler`가 처리하는 오류의 정책이다. 보안 필터·`/error`·의존 라이브러리의 모든 로그에 적용되는 정책은 아니다.

| 오류 | 응답 | 로그 |
| --- | --- | --- |
| LOCK_TIMEOUT | 503, Retry-After: 1 | WARN |
| DB_UNAVAILABLE | 503, Retry-After: 1 | WARN, 선별 예외 진단 |
| RATE_UNAVAILABLE / RATE_STALE | 503, Retry-After 없음 | WARN |
| 일반 업무·MVC 입력 오류 | 해당 4xx | DEBUG |
| UNKNOWN_ERROR 업무 오류·예상 밖 예외 | 500 | ERROR |

`DB_UNAVAILABLE`은 도메인 ErrorCode가 아닌 웹 어댑터 분류다. `DatabaseUnavailability`가 원인 연쇄의 SQLState 08xxx(08P01 프로토콜 위반 제외)·57P01~03과 JDBC 연결 예외를 판별한다. 잠금·제약 위반을 이 분류로 합치지 않는다. 고정 1초의 Retry-After는 성공 보장이나 실제 복구 예상 시간이 아니다. 커밋 도중 장애도 같은 503이 될 수 있어 접수 여부는 응답만으로 확정할 수 없다. 최초 요청부터 보낸 같은 키·견적으로 재시도한다.

직접 작성한 오류 로그는 메서드·경로·요청 ID·오류 코드와 제한된 예외 설명을 남긴다. 경로는 쿼리스트링을 제외하고 출력 불가 문자 등을 치환하며 200자로 제한한다. `ErrorDescriptions`는 예외 클래스 연쇄 최대 10개, SQLState·벤더 코드, 앱 스택 프레임 최대 5개를 기록한다. 메시지 원문·전체 스택·본문·Authorization·토큰은 출력하지 않는다. 경로 안 개인정보를 의미적으로 지우지는 않으므로 URI에 민감 정보를 넣지 않는다.

이 선택은 정보 노출을 줄이는 대신 깊은 라이브러리 원인을 잃어 안전한 데이터로 재현해야 할 수 있다. 일반 4xx가 DEBUG이므로 기본 INFO 로그로 전체 오류율을 계산할 수 없다. `JsonErrorController`는 원시 메시지 없이 같은 JSON 오류 형식을 제공하지만 직접 로깅하지 않는다.

## DB·풀·기동 진단의 경계

| 진단 수단 | 무엇을 알려 주나 | 한계 |
| --- | --- | --- |
| RollbackSafeTransactionManager | 원래 예외를 보존하고 롤백 실패 클래스·SQLState를 WARN으로 기록 | 롤백 성공·DB 정리 완료의 증거가 아님 |
| DatabaseConnectionFailureAnalyzer | Flyway 접속 실패의 인증(28xxx)·DB 없음(3D000)·연결(08xxx), Hibernate Dialect 실패 안내 | 기동 시점 분류이며 실행 중 health check 아님 |
| LockTimeoutFailureAnalyzer | 잠금 설정의 1~30000ms ASCII 정수 제약 위반 안내 | DB 연결 전 설정 검증 |
| SocketTimeoutFailureAnalyzer | 소켓 설정의 1~3600초 및 잠금 대기보다 큰 값 제약 위반 안내 | PostgreSQL 읽기 제한이며 HTTP 전체 deadline 아님 |
| Hikari 로그 | 풀 생성·연결 검증·접속 실패의 드라이버 진단 | FailureAnalyzer의 출력 제한과 별개, 라이브러리 메시지가 남을 수 있음 |

FailureAnalyzer는 안내에 비밀번호·사용자명·호스트·URL 원문을 넣지 않는다. 주변 Hikari 등 라이브러리 로그까지 정제하는 장치는 아니다. 기본 설정에서도 연결 실패 WARN이 나올 수 있으므로 ‘기본 로그는 모두 안전하다’고 해석하지 않는다.

`local`은 Hibernate SQL DEBUG·SqlExceptionHelper WARN·Hikari DEBUG를 켠다. 바인딩 TRACE는 기본 비활성이며 주석 처리돼 있다. SQL의 `?`와 실제 바인딩 값 출력을 구분한다. 제약 오류나 드라이버 메시지에 식별정보가 섞일 수 있어 로컬 테스트 데이터로 진단한다. `local`만 지정하면 기본 postgres가 대체돼 H2이고, PostgreSQL 진단은 `postgres,local`이다. 자세한 실행법은 [README](../../README.md#로컬-h2-콘솔과-로그h2로-실행)를 따른다.

## 실패를 조사하는 순서

1. HTTP 상태·오류 코드·X-Request-Id를 확인하고 해당 ID의 WARN/ERROR를 찾는다. 없으면 DEBUG 정책 또는 핸들러 밖의 오류 경로인지 구분한다.
2. LOCK_TIMEOUT은 같은 사용자 경합·긴 트랜잭션을, DB_UNAVAILABLE은 연결·DB·풀 상태를 조사한다. 오류 코드만으로 원인을 확정하지 않는다.
3. 환율 오류는 설정과 모의 공급처 상태를 확인한다. 실제 외부 금융 API 장애로 해석하지 않는다.
4. 예상 밖 오류는 클래스·SQLState·앱 프레임으로 위치를 좁힌다. 앱이 기동하지 않았다면 요청 ID 대신 FailureAnalyzer와 기동 로그를 확인한다.
5. 접수 성공 여부가 불확실하면 [멱등성 계약](concurrency.md)에 따라 같은 키 재시도·이력으로 확인한다. 롤백 경고나 요청 ID는 성공·실패 증거가 아니다.

## 검증과 남은 범위

`ObservabilityTest`는 요청 ID·MDC 정리·선별 진단·본문 미기록·로그 수준을, `LocalLoggingTest`는 프로파일 차이를 검사한다. DB 기동·잠금·소켓 설정 테스트와 PostgreSQL 장애 테스트는 각 실패 분류를 확인한다. 실제 실행 범위와 과거 장애·부하 실험은 [검증 결과](../portfolio/validation.md)에 모았다. 테스트 입력에서 비밀 값이 안 나왔다는 것을 모든 URI·라이브러리·비동기 경로에 대한 증명으로 확대하지 않는다.

앱의 상시 메트릭 수집·중앙 로그·분산 트레이스·대시보드·알림은 구성하지 않았다. k6의 일회성 부하 관찰은 운영 관측 시스템이 아니다. 다음 후보는 요청 시간·풀 점유 메트릭과 구조화 로그이며, 실제 운영 요구가 생기면 수집·보관·접근 정책까지 함께 정한다. 요청 ID·사용자 ID·멱등성 키를 메트릭 라벨에 넣는 고카디널리티 설계는 피한다. 오류 로그는 업무 감사 원장이 아니다.
