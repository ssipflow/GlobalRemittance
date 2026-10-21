# 로컬 실행과 진단 가이드

Java 17, Docker Compose v2, 저장소 Gradle wrapper를 기준으로 한다. 새 DB의 키 생성과 실행은 [README](../../README.md#먼저-실행하기)를 따른다. 아래 명령은 저장소 루트에서 실행한다.

DB 비밀번호는 필수다(compose가 비어 있으면 중단한다). PostgreSQL 데이터는 Docker 볼륨 `remittance-pg-data`에 남으며 `docker compose down`으로 컨테이너만 지운다. 비밀번호는 볼륨을 처음 만들 때 고정되므로 바꾸려면 데이터를 버려도 될 때 `docker compose down -v`로 볼륨을 지우고 다시 만든다.
Docker 없이 embedded H2 파일 DB로 실행하려면 `SPRING_PROFILES_ACTIVE=h2 ./gradlew bootRun`을 쓴다. 파일 DB는 `./data/remittance`이며 기본 실행에서는 사용하지 않고 그대로 보존한다.

AES 키로 암호화한 식별번호를 읽으려면 처음 사용한 AES 키를 유지해야 한다. 위 예시를 매번 실행해 기존 키를 바꾸지 않는다. 환경변수의 export 구문을 `secret.local`에 두고 `source ./secret.local`로 읽을 수도 있다. 이 파일은 Git에서 제외되며 실제 값을 문서·PR에 붙여 넣지 않는다.

실행 후 `/swagger-ui.html`에서 API를 열고 회원가입·로그인한다. 로그인 응답의 token을 **Authorize**에 넣어 송금 API를 호출한다. OpenAPI JSON은 `/v3/api-docs`다. 두 경로는 인증 없이 열리며 `REMITTANCE_API_DOCS_ENABLED=false`로 함께 끈다.

## 설정

| 환경변수 | 의미 | 기본값 |
| --- | --- | --- |
| REMITTANCE_AES_KEY | 식별번호 AES-GCM 암호화 키 | 필수 |
| REMITTANCE_JWT_SECRET | JWT 서명 키 | 필수 |
| REMITTANCE_DB_PASSWORD | DB 비밀번호. PostgreSQL(compose)은 필수, `h2` 프로파일의 sa 비밀번호는 빈 값 허용 | 빈 값 |
| REMITTANCE_USD_TO_KRW | USD 1단위당 KRW | 1300, 예시 환율 |
| REMITTANCE_JPY_TO_KRW | JPY 1단위당 KRW | 9, 예시 환율 |
| REMITTANCE_RATES_MAX_AGE_SECONDS | 환율 기준 시각 허용 지연(초, 1~3600). 넘으면 견적 503 `RATE_STALE` | 60 |
| REMITTANCE_RATES_SOURCE | `configured`(설정 환율) 또는 `simulated`(지연·장애를 주입하는 모의 공급처, 운영 사용 금지) | configured |
| REMITTANCE_RATES_SIMULATED_AGE_SECONDS / _UNAVAILABLE | 모의 공급처의 지연(초)과 장애 여부 | 0 / false |
| REMITTANCE_DB_SOCKET_TIMEOUT_SECONDS | PostgreSQL JDBC 소켓 읽기 대기 한도(초, 1~3600). 잠금 대기 한도보다 커야 한다 | 60 |
| REMITTANCE_LOCK_TIMEOUT_MS | 잠금 대기 한도(ms). PostgreSQL은 `SET lock_timeout`, H2는 URL `LOCK_TIMEOUT` | 5000 |
| REMITTANCE_API_DOCS_ENABLED | Swagger UI·OpenAPI 노출 | true |
| SPRING_PROFILES_ACTIVE | 지정하지 않으면 `postgres`. `h2`는 H2 파일 DB, `local`은 H2 콘솔·SQL 진단(H2로 실행). PostgreSQL에서 진단 로그를 쓰려면 `postgres,local` | 지정하지 않음 |

잠금 대기값은 1~30000의 ASCII 십진 정수다. 부호·공백·소수·단위·선행 0은 허용하지 않는다. 잘못된 값은 DB 연결 전에 거부하고 시작 실패 안내를 출력한다. 이 설정은 잠금 대기 한도이며 HTTP 요청 전체의 시간 제한은 아니다.

환경변수 대신 애플리케이션 인자를 전달할 수도 있다.

```sh
./gradlew bootRun --args="--remittance.aes-key=<기존 AES 키> --remittance.jwt-secret=<기존 JWT 키> --remittance.usd-to-krw=1350"
```

동일 속성의 앱 인자는 환경변수보다 우선한다. 인자는 프로세스 목록·셸 기록에 노출될 수 있어 로컬 개발 대안으로만 사용한다. `./gradlew -Dremittance.aes-key=...`는 Gradle JVM 속성이므로 bootRun 앱에 자동 전달되지 않는다.

### 환율 신선도 정책

환율에는 기준 시각이 붙고, 기준 시각이 현재와 허용 지연(기본 60초)을 초과해 벗어나거나(같으면 신선) 조회가 실패하면 견적 발급을 503(`RATE_STALE`/`RATE_UNAVAILABLE`)으로 거부한다. 직전 값을 몰래 쓰지 않는다. 접수는 환율을 조회하지 않으므로 이미 발급된 견적에는 영향이 없다. 기본 공급처(`configured`)는 설정값을 항상 현재 시각으로 돌려줘 동작이 이전과 같다.

실제 시세 공급처는 붙이지 않았다. 정책을 시연·검증하려고 모의 공급처를 두었으며 `REMITTANCE_RATES_SOURCE=simulated`에 `REMITTANCE_RATES_SIMULATED_AGE_SECONDS=120` 또는 `REMITTANCE_RATES_SIMULATED_UNAVAILABLE=true`를 주면 지연·장애를 재현한다(기동 시 WARN 로그가 나온다). 잘못된 값(범위 밖·형식 오류)은 기동 시점에 거부하되(모의 공급처 값은 `source=simulated`일 때만 검사하고, `configured`에서는 무시한다. 선행 0은 허용한다), DB 초기화 뒤에 검사하므로, 잠금 대기 설정과 달리 거부되기 전에 `./data`가 만들어질 수 있다.

### DB 비밀번호와 시작 실패

`h2` 프로파일의 H2 비밀번호는 파일 DB를 처음 만들 때 정해진다. 기본 빈 비밀번호로 만든 DB에 나중에 다른 REMITTANCE_DB_PASSWORD를 지정해도 기존 비밀번호가 변경되지는 않는다. 새 DB를 만들 때만 원하는 값을 지정하고, 기존 DB는 만들 때의 값을 사용한다.

DB 접속에 실패하면 Flyway 초기화 단계(`flywayInitializer`)에서 먼저 실패하며, 시작 실패 분석기(DatabaseConnectionFailureAnalyzer)가 `APPLICATION FAILED TO START` 블록으로 원인을 SQLState로 분류해 안내한다: 인증 거부(`28xxx`, H2 28000·PostgreSQL 28P01), 서버에 연결 불가(`08xxx`), 데이터베이스 없음(`3D000`), 그 밖의 접속 실패. 안내에는 비밀번호·사용자명·호스트·JDBC URL을 넣지 않고 SQLState 코드만 보여 준다. 조치 문구는 예외 클래스명으로 H2와 PostgreSQL을 구분한다(H2는 파일 DB 비밀번호 고정·`trace.db`, PostgreSQL은 `pg_hba.conf`). 안내 블록 아래에는 스택이 나오지 않고, 라이브러리의 원래 메시지는 블록 바로 위 WARN 로그 한 줄에 있으며 사용자명이나 호스트:포트가 포함될 수 있다(직접 확인: 비밀번호와 JDBC URL은 없었다). H2 프로파일의 틀린 비밀번호와 PostgreSQL의 틀린 비밀번호·닫힌 포트·없는 DB를 실제 jar 기동으로 확인했다. 기존 키·DB 비밀번호를 확인하고 로컬 프로파일의 연결 풀 로그나 `data/remittance.trace.db`를 살펴본다.

데이터를 유지해야 하면 기존 비밀번호로 접속해 H2의 ALTER USER로 변경한다. DB를 재생성하는 방법은 기존 로컬 데이터를 버려도 되는 경우에만 사용한다. DB 접속 비밀번호가 파일 내용을 암호화하는 것은 아니다.

### 로컬 H2 콘솔과 로그(H2로 실행)

```sh
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`/h2-console`에 JDBC URL `jdbc:h2:file:./data/remittance`, 사용자 `sa`, 기존 DB 비밀번호로 접속한다. 앱과 같은 프로세스에서 파일을 열므로 별도 H2 프로세스로 여는 경우의 파일 잠금 충돌을 피할 수 있다.

local은 콘솔을 켜고 프레임 정책을 SAMEORIGIN으로 바꾼다. 콘솔은 앱 JWT 대신 DB 로그인으로 보호되며 기본 DB 비밀번호는 빈 값이다. 공유 환경에 local을 적용하지 않는다. 기본 실행은 콘솔이 꺼지고 프레임 정책은 DENY다.

local에서는 Hibernate SQL DEBUG, SqlExceptionHelper WARN, Hikari DEBUG를 켠다. SQL 바인딩 값은 기본적으로 출력하지 않지만 DB 오류에는 이메일이 포함될 수 있고 연결 풀 진단에는 URL·사용자명이 보일 수 있다. 바인딩 TRACE를 추가하면 해시·암호문도 기록될 수 있으므로 테스트 데이터에서만 사용한다. 기본 프로파일의 오류 로그 정책과 구분한다.

### Flyway 스키마 관리

H2는 `src/main/resources/db/migration`, PostgreSQL은 `src/main/resources/db/postgresql/migration`의 버전 SQL로 스키마를 적용하고 Hibernate는 `ddl-auto: validate`로 컬럼·타입을 검사한다.

| 버전 | 내용 |
| --- | --- |
| V1 | users·remittances 및 사용자/접수시각 인덱스 |
| V2 | idempotency_key와 (user_id, idempotency_key) 유일 제약 |
| V3 | 기존 users.user_id 소문자 정규화, 대소문자만 다른 중복 계정은 실패 |

두 DB 모두 새 DB는 V1부터 적용한다. H2만 `baseline-on-migrate: true`, `baseline-version: 0`으로 Flyway 이력이 없는 비어 있지 않은 기존 파일 DB에 기준선을 잡은 뒤 V1~V3을 적용한다. 기본 PostgreSQL은 `baseline-on-migrate: false`이며, 이력 없는 비어 있지 않은 DB를 자동으로 인수하지 않는다. V3은 기존 `users.user_id`를 소문자로 바꾸며, 대소문자만 다른 중복 계정이 있으면 합치지 않고 실패하므로 운영자가 직접 정리한 뒤 다시 실행한다. H2 V1·V2의 IF NOT EXISTS는 이전 앱이 만든 객체를 보존하기 위한 것이며 임의의 구 스키마를 정상 상태로 복구한다는 의미는 아니다. 적용 이력은 flyway_schema_history에 저장한다.

적용한 SQL을 수정하지 않고 새 번호로 변경을 추가한다. Hibernate validate만으로 모든 인덱스·유일 제약·FK 이름을 검사한다고 가정하지 않는다. H2 기존 데이터 업그레이드는 MigrationTest에서 다루며 다른 시스템의 데이터를 이전하는 도구는 제공하지 않는다.

### PostgreSQL(기본 실행)

프로파일을 지정하지 않으면 `postgres` 프로파일이 적용된다. [README의 먼저 실행하기](../../README.md#먼저-실행하기)의 `docker compose up -d --wait`가 `docker-compose.yml`의 PostgreSQL 16을 띄운다.
`127.0.0.1`로 묶어 같은 PC에서만 접속되고, healthcheck(`pg_isready`)로 준비를 기다린다. 앱은 호스트에서 `./gradlew bootRun`으로 실행한다.
`docker compose down`은 컨테이너만 지우고 데이터(볼륨)는 남긴다. 데이터까지 지우려면 `docker compose down -v`를 쓴다. compose 명령은 모두 `REMITTANCE_DB_PASSWORD`가 환경에 있어야 하므로 새 셸에서는 `source ./secret.local`(또는 export)을 먼저 한다.
DB가 떠 있지 않으면 앱은 기동 시 Flyway 접속 실패로 종료하며 시작 실패 분석기가 SQLState로 원인을 안내한다.

| 환경변수 | 용도 | 기본값 |
| --- | --- | --- |
| REMITTANCE_DB_URL | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/remittance` |
| REMITTANCE_DB_USER | 접속 사용자. compose의 `POSTGRES_USER`와 앱이 같은 값을 써야 한다 | `remittance` |
| REMITTANCE_DB_PASSWORD | 접속 비밀번호. compose의 `POSTGRES_PASSWORD`와 같은 값(H2와 같은 변수) | 필수 |

- 스키마는 `src/main/resources/db/postgresql/migration`의 PostgreSQL 전용 SQL로 만든다. H2용 `db/migration`의 하위 폴더가 아니다(Flyway가 하위까지 읽기 때문).
  H2의 `ENUM`은 `VARCHAR + CHECK`로 옮겼다. 새 DB 전용이라 `IF NOT EXISTS`를 쓰지 않으며, 이력 없는 기존 DB를 자동으로 인수하지 않는다.
- 잠금 대기 한도(`REMITTANCE_LOCK_TIMEOUT_MS`)는 연결마다 `SET lock_timeout`으로 적용한다(H2의 URL `LOCK_TIMEOUT`에 해당).
- 검증은 Docker가 필요한 `./gradlew postgresTest`로 따로 실행한다. 기본 `check`에는 포함되지 않아 Docker 없이도 통과한다.
- `postgresTest`가 PostgreSQL 16에서 검증하는 것: 스키마·Hibernate validate·기동·기본 API 흐름·`lock_timeout` 설정, 같은 견적/다른 견적(일일 한도)/같은 멱등성 키의 동시 접수(6스레드),
  잠금 대기 초과의 503 `LOCK_TIMEOUT`(`Retry-After: 1`)과 해제 후 성공, 잠금 중에도 막히지 않는 이력 조회, DB가 연결을 모두 끊은 뒤의 풀 복구, 트랜잭션 도중 연결이 끊겨 롤백이 실패해도 원래 업무 오류가 보존되고 쓰기가 남지 않으며 풀이 복구됨.
- 후속 검증: 같은 JVM의 독립 앱 컨텍스트 2개(별도 HTTP 서버·풀)에서 분산 동시 접수, 풀 3개·32스레드 폭주, DB 정상 재시작·강제 종료·pause, Toxiproxy 블랙홀·리셋·지연, COMMIT 전·후 응답 유실을 확인했다. 구체적인 구성과 한계는 [검증 범위](../portfolio/validation.md)에 구분했다.
- DB 연결 장애는 `Retry-After: 1`이 붙은 503 `DB_UNAVAILABLE`로 응답한다. 503만으로 커밋 여부를 알 수 없으므로 처음부터 같은 `Idempotency-Key`·견적으로 재시도한다. COMMIT 전·후 결함 주입에서 접수와 집계가 1회로 수렴함을 확인했다. 재현 창은 24시간 미만이며 키 없는 재시도는 이력으로 확인한다.
- **남은 한계**: 별도 앱 프로세스·호스트 장애, 교착, 다른 PostgreSQL 버전·관리형 서비스·운영 규모는 검증하지 않았다. 소켓 타임아웃은 개별 읽기 대기 제한이며 HTTP 전체 처리 시간의 상한이 아니다.

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
| 400 | INVALID_REQUEST, NEGATIVE_NUMBER, AMOUNT_TOO_SMALL, QUOTE_EXPRIED, LIMIT_EXCESS |
| 401 / 403 | UNAUTHORIZED / FORBIDDEN |
| 404 | QUOTE_NOT_FOUND, NOT_FOUND |
| 409 / 422 | DUPLICATE_USER, ALREADY_REQUESTED / IDEMPOTENCY_KEY_REUSED |
| 405 / 406 / 415 | METHOD_NOT_ALLOWED / NOT_ACCEPTABLE / UNSUPPORTED_MEDIA_TYPE |
| 500 / 503 | UNKNOWN_ERROR / LOCK_TIMEOUT, DB_UNAVAILABLE |

LOCK_TIMEOUT과 DB_UNAVAILABLE만 `Retry-After: 1`을 반환한다. 고정 1초의 재시도 안내이며 그 뒤 성공을 보장하거나 서버가 자동 재시도하는 기능은 아니다. 원 요구사항 철자인 QUOTE_EXPRIED를 유지한다.

응답의 `X-Request-Id`로 로그를 찾는다. 클라이언트가 `[A-Za-z0-9._-]{1,64}` 값을 보내면 채택하고 그 외에는 UUID를 생성한다. 멱등성 키와 용도가 다르며 오류 JSON 본문에는 넣지 않는다. 기본 오류 로그는 예외 메시지·본문·쿼리스트링·토큰 대신 코드·요청 위치·예외 클래스·SQLState 등의 선별 정보를 남긴다. 보안 필터와 /error의 모든 실패를 이 예외 로깅이 수집하는 것은 아니다.

## k6 부하 테스트

로컬에서 실행 중인 앱에 부하를 거는 k6 스크립트가 `k6/`에 있다. CI에는 연결하지 않았고 필요할 때 직접 실행한다.

- 전제: [k6](https://k6.io) 설치(v1.1.0에서 확인), 앱이 `BASE_URL`(기본 `http://localhost:8080`)에서 실행 중. [README의 먼저 실행하기](../../README.md#먼저-실행하기)로 PostgreSQL과 앱을 띄운다.
- 부하 테스트 사용자는 `k6load-<RUN_ID>-vu<번호>-u<순번>@example.com`으로 가입해 구분한다. 지우는 도구는 없으므로 개인 데이터와 섞이지 않는 로컬 DB에서 실행한다.

```sh
k6 run k6/transfer-flow.js                      # 점진 램프업 접수 흐름(약 2분, 최대 30 VU)
k6 run k6/spike.js                              # 5 -> 100 VU 급증 후 복귀(약 95초)
DURATION=5m VUS=20 k6 run k6/soak.js            # 지속 부하(기본 5m, 20 VU)
SCENARIO=transfer k6 run k6/capacity.js         # 한계 탐색: 초당 요청 수 계단식 증가(접수 흐름)
SCENARIO=auth k6 run k6/capacity.js             # 한계 탐색: 로그인(BCrypt) 처리량
SCENARIO=signup k6 run k6/capacity.js           # 한계 탐색: 가입(BCrypt·AES·DB 쓰기) 처리량
SCENARIO=list k6 run k6/capacity.js             # 한계 탐색: 이력 조회(읽기) 처리량
```

| 환경변수 | 의미 | 기본값 |
| --- | --- | --- |
| BASE_URL | 대상 서버 | `http://localhost:8080` |
| VUS | transfer-flow 최대 VU, soak VU | 30 / 20 |
| BASE_VUS, SPIKE_VUS | spike의 기준·급증 VU | 5 / 100 |
| DURATION | soak 지속 시간(`90s`, `5m`, `1h`) | `5m` |
| SCENARIO, STEPS, STEP_SECONDS, RAMP_SECONDS | capacity의 시나리오(`auth`/`transfer`/`signup`/`list`), 계단별 초당 반복 수(쉼표 목록), 단계 유지·증가 초 | `transfer`, 시나리오별 기본 목록(transfer `50,100,200,300,400,600`, auth·signup `5,10,15,20,30,40,60`, list `100,200,400,600,800,1000`), 30, 5 |
| USERS, PRE_VUS, MAX_VUS | capacity의 사전 가입 사용자 수(signup은 쓰지 않음, list는 사용자당 접수 3건을 미리 만듦), 사전 할당·최대 VU | 1000(auth·list 100), 200, 1500. transfer는 사용자당 일일 한도(소액도 약 250건)가 소진되지 않게 총 반복 수/250보다 크게 둔다 |
| RUN_ID, LOAD_USER_PASSWORD | 사용자 이메일 접두사, 예시 비밀번호 | 현재 시각(VU마다 달라 실행 단위 구분엔 직접 지정 권장) / 예시 값 |

결과 해석: 종료 시 `THRESHOLDS`의 모든 항목이 체크(통과)여야 한다. 핵심은 `unexpected_responses`(계약 밖 응답, 500 `UNKNOWN_ERROR`·401 등)가 0건이고 `invariant_violations`(같은 키 재시도의 200 재현, 키 없는 재시도의 409)가 0건인 것이다.
`ALREADY_REQUESTED`·`LIMIT_EXCESS`(`expected_rejections`)와 정의된 503(`defined_503`, 코드별 태그)은 계약에 정의된 응답으로 별도 집계한다. 정의된 503은 `unexpected_responses`·`http_req_failed`에서는 제외하지만 `server_error_rate`에 포함되고 `flow_healthy_rate`에서는 실패로 센다. spike는 `phase:recovery`, soak는 `early/mid/late` 구간별 지연으로 급증 뒤 회복과 시간에 따른 급격한 악화를 본다(상한이 관찰값보다 넉넉해 완만한 악화는 놓칠 수 있다).
`DURATION=2h`로 20 VU 소크를 1회 돌린 과거 관찰값과 한계는 [검증 결과](../portfolio/validation.md#과거-실행에서-관찰한-수치)와 [작업 기록](../harness/tasks/postgres-failure-modes.md)에 있다. 넉넉한 절대 임계값에서 통과했다는 것이 완만한 악화나 메모리 누수를 배제하지는 않는다.
스크립트는 한 번에 하나씩 단독으로 실행한다. 동시에 돌리면 BCrypt(가입·로그인) 지연이 급증해 임계값이 실패한다(같은 코드를 단독 실행하면 가입·로그인 p95가 약 0.4초). `capacity.js`는 임계값 통과가 아니라 단계별 결과(`http_req_duration{step:rN}`, `dropped_iterations`)를 읽어 한계를 찾는 탐색용이며, 모든 임계값이 항상 참이라 실패로 끝나지 않는다.
임계값은 이 Mac에서 관찰한 값에 여유를 둔 것이며(근거: [k6 작업 기록](../harness/tasks/k6-load-test.md)), 로컬 단일 인스턴스·단일 PostgreSQL 컨테이너에서 측정한 값이다. 운영 성능이나 용량을 보증하지 않는다.


## 포맷 수정

검증 명령은 [README 검증](../../README.md#검증)에 모았다. `./gradlew spotlessApply`는 파일을 수정하는 선택적 포맷 명령이며 검증 자체가 아니다. 수정 후 다시 검증한다. Google Java Format 1.22.0 AOSP·Spotless 6.25.0, Checkstyle 10.17.0을 사용한다.
