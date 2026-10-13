# GlobalRemittance

원화로 USD·JPY 송금 견적을 만들고 접수하는 개인 프로젝트다. Java 17 / Spring Boot 3 / JPA / embedded H2를 사용하며 기본 패키지는 `com.nelson.remittance`다.

환율·수수료를 확정한 견적, 사용자별 일일 한도와 동시 접수, 성공 응답의 멱등 재현, 커서 기반 이력 조회를 구현했다. 접수는 DB에 기록하는 단계까지이며 실제 금융기관 지급과 실시간 시세 공급은 구현하지 않았다.

## 먼저 실행하기

Java 17 이상과 저장소의 Gradle wrapper를 사용한다. AES 키는 Base64 32바이트, JWT 키는 Base64 32바이트 이상이어야 한다.

```sh
# 새 로컬 DB에서 사용할 개발 키 생성 예시
export REMITTANCE_AES_KEY="$(openssl rand -base64 32)"
export REMITTANCE_JWT_SECRET="$(openssl rand -base64 32)"
./gradlew bootRun
```

파일 DB는 `./data/remittance`다. 기존 식별번호를 읽으려면 처음 사용한 AES 키를 유지해야 한다. 위 예시를 매번 실행해 기존 키를 바꾸지 않는다. 환경변수의 export 구문을 `secret.local`에 두고 `source ./secret.local`로 읽을 수도 있다. 이 파일은 Git에서 제외되며 실제 값을 문서·PR에 붙여 넣지 않는다.

실행 후 `/swagger-ui.html`에서 API를 열고 회원가입·로그인한다. 로그인 응답의 token을 **Authorize**에 넣어 송금 API를 호출한다. OpenAPI JSON은 `/v3/api-docs`다. 두 경로는 인증 없이 열리며 `REMITTANCE_API_DOCS_ENABLED=false`로 함께 끈다.

## 설정

| 환경변수 | 의미 | 기본값 |
| --- | --- | --- |
| REMITTANCE_AES_KEY | 식별번호 AES-GCM 암호화 키 | 필수 |
| REMITTANCE_JWT_SECRET | JWT 서명 키 | 필수 |
| REMITTANCE_DB_PASSWORD | 파일 H2의 sa 비밀번호 | 빈 값 |
| REMITTANCE_USD_TO_KRW | USD 1단위당 KRW | 1300, 예시 환율 |
| REMITTANCE_JPY_TO_KRW | JPY 1단위당 KRW | 9, 예시 환율 |
| REMITTANCE_LOCK_TIMEOUT_MS | H2 잠금 대기 한도(ms) | 5000 |
| REMITTANCE_API_DOCS_ENABLED | Swagger UI·OpenAPI 노출 | true |
| SPRING_PROFILES_ACTIVE | local이면 H2 콘솔·SQL 진단 활성화 | 지정하지 않음 |

잠금 대기값은 1~30000의 ASCII 십진 정수다. 부호·공백·소수·단위·선행 0은 허용하지 않는다. 잘못된 값은 DB 연결 전에 거부하고 시작 실패 안내를 출력한다. 이 설정은 잠금 대기 한도이며 HTTP 요청 전체의 시간 제한은 아니다.

환경변수 대신 애플리케이션 인자를 전달할 수도 있다.

```sh
./gradlew bootRun --args="--remittance.aes-key=<기존 AES 키> --remittance.jwt-secret=<기존 JWT 키> --remittance.usd-to-krw=1350"
```

동일 속성의 앱 인자는 환경변수보다 우선한다. 인자는 프로세스 목록·셸 기록에 노출될 수 있어 로컬 개발 대안으로만 사용한다. `./gradlew -Dremittance.aes-key=...`는 Gradle JVM 속성이므로 bootRun 앱에 자동 전달되지 않는다.

### DB 비밀번호와 시작 실패

H2 비밀번호는 파일 DB를 처음 만들 때 정해진다. 기본 빈 비밀번호로 만든 DB에 나중에 다른 REMITTANCE_DB_PASSWORD를 지정해도 기존 비밀번호가 변경되지는 않는다. 새 DB를 만들 때만 원하는 값을 지정하고, 기존 DB는 만들 때의 값을 사용한다.

DB 접속 실패 시 시작 실패 분석기가 원인 확인 방향을 안내한다. `Unable to determine Dialect without JDBC metadata`는 비밀번호 불일치 등으로 연결 정보 조회가 실패할 때 나타날 수 있으며, 그 문구만으로 원인을 확정하지 않는다. 기존 키·DB 비밀번호를 확인하고 로컬 프로파일의 연결 풀 로그나 `data/remittance.trace.db`를 살펴본다.

데이터를 유지해야 하면 기존 비밀번호로 접속해 H2의 ALTER USER로 변경한다. DB를 재생성하는 방법은 기존 로컬 데이터를 버려도 되는 경우에만 사용한다. DB 접속 비밀번호가 파일 내용을 암호화하는 것은 아니다.

### 로컬 H2 콘솔과 로그

```sh
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`/h2-console`에 JDBC URL `jdbc:h2:file:./data/remittance`, 사용자 `sa`, 기존 DB 비밀번호로 접속한다. 앱과 같은 프로세스에서 파일을 열므로 별도 H2 프로세스로 여는 경우의 파일 잠금 충돌을 피할 수 있다.

local은 콘솔을 켜고 프레임 정책을 SAMEORIGIN으로 바꾼다. 콘솔은 앱 JWT 대신 DB 로그인으로 보호되며 기본 DB 비밀번호는 빈 값이다. 공유 환경에 local을 적용하지 않는다. 기본 실행은 콘솔이 꺼지고 프레임 정책은 DENY다.

local에서는 Hibernate SQL DEBUG, SqlExceptionHelper WARN, Hikari DEBUG를 켠다. SQL 바인딩 값은 기본적으로 출력하지 않지만 DB 오류에는 이메일이 포함될 수 있고 연결 풀 진단에는 URL·사용자명이 보일 수 있다. 바인딩 TRACE를 추가하면 해시·암호문도 기록될 수 있으므로 테스트 데이터에서만 사용한다. 기본 프로파일의 오류 로그 정책과 구분한다.

### Flyway 스키마 관리

`src/main/resources/db/migration`의 버전 SQL로 스키마를 적용하고 Hibernate는 `ddl-auto: validate`로 컬럼·타입을 검사한다.

| 버전 | 내용 |
| --- | --- |
| V1 | users·remittances 및 사용자/접수시각 인덱스 |
| V2 | idempotency_key와 (user_id, idempotency_key) 유일 제약 |

새 DB는 V1부터 적용한다. Flyway 이력이 없는 비어 있지 않은 DB는 버전 0으로 기준선을 잡은 뒤 V1·V2를 적용한다. 두 SQL의 IF NOT EXISTS는 이전 앱이 만든 객체를 보존하기 위한 것이며 임의의 구 스키마를 정상 상태로 복구한다는 의미는 아니다. 적용 이력은 flyway_schema_history에 저장한다.

적용한 SQL을 수정하지 않고 새 번호로 변경을 추가한다. Hibernate validate만으로 모든 인덱스·유일 제약·FK 이름을 검사한다고 가정하지 않는다. 기존 데이터 업그레이드는 MigrationTest에서 다루며 다른 시스템의 데이터를 이전하는 도구는 제공하지 않는다.

## API 사용

본문 요청은 `Content-Type: application/json`, 송금 API는 `Authorization: Bearer <token>`을 사용한다. 성공은 HTTP 200과 `{"resultCode":200,"resultMsg":"OK"}`가 기본이다.

| API | 입력 | 주요 응답 |
| --- | --- | --- |
| POST /user/signup | userId, password, name, idType, idValue | 기본 응답 |
| POST /user/login | userId, password | token |
| POST /transfer/quote | amount(KRW), targetCurrency(USD/JPY) | quoteId, exchangeRate, expireTime, targetAmount |
| POST /transfer/request | quoteId, 선택 Idempotency-Key 헤더 | 기본 응답 |
| GET /transfer/list | 선택 size·cursor | 사용자, 오늘 집계, history, nextCursor |

JWT는 30분, 견적은 10분 유효하며 만료 순간부터 거부한다. API 견적·이력 시각은 KST `yyyy-MM-dd HH:mm:ss`다. 이력은 접수시각·ID 내림차순으로 기본 50건, 최대 100건씩 조회한다. 오늘 건수·합계는 페이지와 무관하게 KST 당일 전체를 집계한다. 목록과 집계는 별개 조회여서 동시 접수와 겹치면 시점이 다를 수 있다.

### 접수 재시도

```sh
TOKEN='<로그인 응답의 token>'
# quoteId는 실제 견적 응답의 값으로 바꾼다.
curl -i -X POST http://localhost:8080/transfer/request \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: demo-transfer-001' \
  -d '{"quoteId":"1"}'
```

키는 `[A-Za-z0-9._~-]{1,64}`이며 사용자별로 구분한다. 처음부터 같은 키·견적을 사용한 성공 요청은 접수 시각부터 24시간 미만 동안 200으로 재현한다. 두 번 접수하거나 일일 합계에 다시 더하지 않는다. 재현은 인증을 생략하지 않으므로 JWT가 만료되면 재로그인해야 한다.

정확히 24시간부터 같은 견적은 409 ALREADY_REQUESTED다. 키의 DB 귀속은 삭제되지 않아 다른 견적에 재사용하면 기간과 관계없이 422 IDEMPOTENCY_KEY_REUSED다. 실패 응답은 저장하지 않는다. 키 없이 성공한 접수에 나중에 키를 붙여도 재현되지 않는다.

### 오류와 추적 헤더

| HTTP | 대표 코드 |
| --- | --- |
| 400 | INVALID_REQUEST, NEGATIVE_NUMBER, QUOTE_EXPRIED, LIMIT_EXCESS |
| 401 / 403 | UNAUTHORIZED / FORBIDDEN |
| 404 | QUOTE_NOT_FOUND, NOT_FOUND |
| 409 / 422 | DUPLICATE_USER, ALREADY_REQUESTED / IDEMPOTENCY_KEY_REUSED |
| 405 / 406 / 415 | METHOD_NOT_ALLOWED / NOT_ACCEPTABLE / UNSUPPORTED_MEDIA_TYPE |
| 500 / 503 | UNKNOWN_ERROR / LOCK_TIMEOUT |

LOCK_TIMEOUT만 `Retry-After: 1`을 반환한다. 고정 1초의 재시도 안내이며 그 뒤 성공을 보장하거나 서버가 자동 재시도하는 기능은 아니다. 원 요구사항 철자인 QUOTE_EXPRIED를 유지한다.

응답의 `X-Request-Id`로 로그를 찾는다. 클라이언트가 `[A-Za-z0-9._-]{1,64}` 값을 보내면 채택하고 그 외에는 UUID를 생성한다. 멱등성 키와 용도가 다르며 오류 JSON 본문에는 넣지 않는다. 기본 오류 로그는 예외 메시지·본문·쿼리스트링·토큰 대신 코드·요청 위치·예외 클래스·SQLState 등의 선별 정보를 남긴다. 보안 필터와 /error의 모든 실패를 이 예외 로깅이 수집하는 것은 아니다.

## 설계와 문서

BigDecimal·HALF_UP과 통화별 자릿수(KRW/JPY 0, USD 2)를 사용한다. 견적 시 환율·수수료·USD 금액을 고정하고, 접수에서는 사용자 DB 행을 잠근 뒤 KST 일일 SUM·한도·중복을 검사한다. 개인은 1000 USD, 법인은 5000 USD까지 허용한다.

기본 패키지의 domain/application은 Spring·JPA·웹 구현에 의존하지 않는다. adapter가 외부 기술을 연결하고 bootstrap이 객체를 조립한다.

- [개요](docs/application/overview.md): 기능과 실행 설정
- [아키텍처](docs/application/architecture.md): 계층, 요청·저장·기동 흐름
- [API 문서](docs/application/api.md): 필드, JSON 예시, 오류·헤더 계약
- [주요 설계](docs/application/design.md): 상세 동시성·멱등성·페이지네이션·관측·마이그레이션 근거와 한계
- [요구사항](docs/requirements/project-goals.md) 및 [초기 재구현 결정](docs/decisions/remittance-rebuild.md)

## 검증

```sh
./gradlew spotlessApply
./gradlew clean check bootJar --no-daemon
python3 -m unittest discover -s scripts/tests -v
```

check는 JUnit 테스트, Spotless, main/test Checkstyle을 실행한다. 포맷은 Google Java Format 1.22.0 AOSP·Spotless 6.25.0, lint는 Checkstyle 10.17.0이다. 검증 결과는 실행한 커밋·환경과 함께 하네스·PR 기록에서 확인하며 이 문서가 새 실행 결과를 대신하지 않는다.

| 검증 영역 | 주요 테스트 |
| --- | --- |
| 금액·반올림·저장 범위 | QuotePolicyTest |
| 암호화·JWT·BCrypt | SecurityAdaptersTest |
| API·동시 접수·멱등성·KST·페이지 조회 | RemittanceApiTest |
| Swagger·인증 및 기본/local 설정 | OpenApiTest, H2ConsoleTest, LocalLoggingTest |
| 요청 ID·오류 로그·시작 실패 진단 | ObservabilityTest |
| 잠금 대기 설정과 Retry-After | LockTimeoutSettingsTest, RemittanceApiTest |
| 스키마 신규 생성·업그레이드 | MigrationTest |
| 내부 계층 의존성 | ArchitectureTest |

현재 검증은 H2와 격리된 테스트 범위다. 운영 DB·다중 인스턴스·지급 기관 장애·외부 로그 수집과 알림은 별도 과제다. 실제 배포·CI/CD는 구성하지 않는다.

에이전트 실행은 [AGENTS.md](AGENTS.md), 옵시디언 연결은 [문서화 사용법](docs/harness/obsidian.md)을 따른다. 일반 작업은 dev PR 생성에서 끝나며 승인·병합은 사람이 한다.
