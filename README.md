# GlobalRemittance

`Goals Of Project`의 회원·송금 API를 Java 17과 Spring Boot 3로 처음부터 구현한다.
기존 애플리케이션을 교체하고 개발 컨벤션·하네스·Git 이력은 유지했다.
요구사항은 [프로젝트 목표](docs/requirements/project-goals.md), 해석과 비용은
[설계 결정](docs/decisions/remittance-rebuild.md)에서 확인할 수 있다.

## 실행

Java 17 이상과 Gradle wrapper를 사용한다. 비밀번호는 BCrypt 해시로 저장하고,
주민/사업자 번호는 랜덤 nonce를 사용하는 AES-GCM으로 암호화한다.
실행 키를 저장소에 넣지 않는다. AES 키는 Base64 32바이트, JWT 키는 Base64 32바이트 이상이다.

```sh
export REMITTANCE_AES_KEY="$(openssl rand -base64 32)"
export REMITTANCE_JWT_SECRET="$(openssl rand -base64 32)"
./gradlew bootRun
```

위 명령은 개발용 키 생성 예시다. 이미 저장한 데이터의 암호화 키를 유지해야 한다.

로컬에서 키를 매번 만들지 않으려면 `export` 줄을 모은 `secret.local` 파일을 만들어
`source ./secret.local` 후 실행한다. 이 파일은 `.gitignore`로 제외되며 저장소에 올리지 않는다.
다른 사람과 공유하거나 PR·이슈·문서에 붙여 넣지 않는다. 이 파일에 `REMITTANCE_DB_PASSWORD`를 넣을 때는
아래 "데이터베이스 비밀번호"의 고정 규칙을 먼저 확인한다.

### 데이터베이스 비밀번호

H2 접속 사용자는 `sa`이고 비밀번호는 `REMITTANCE_DB_PASSWORD`로 지정한다. 기본값은 빈 값이다.

```sh
export REMITTANCE_DB_PASSWORD="$(openssl rand -base64 18)"
./gradlew bootRun
```

비밀번호는 `./data/remittance`를 **처음 만들 때 고정**된다. 이미 빈 비밀번호로 만들어진 DB에
환경변수만 추가하거나, 다른 비밀번호로 실행하면 `Wrong user name or password`(H2 28000)로 거부되어
기동에 실패한다. 이때 앱 로그에는 `Unable to determine Dialect without JDBC metadata`만 보이고 인증 오류는
나타나지 않는다(개인정보 보호를 위해 `SqlExceptionHelper` 로그를 껐기 때문이다).
비밀번호를 쓰려면 새 DB 파일로 시작하거나, 기존 비밀번호로 접속해 H2의 `ALTER USER`로 바꾼다.
이 비밀번호는 접속 인증용이며 DB 파일 내용을 암호화하지는 않는다.

환경변수 대신 Gradle 인자로 넘길 수도 있다. `bootRun --args`는 애플리케이션 인자로 전달되고,
같은 이름의 설정(`remittance.aes-key` 등)을 환경변수보다 우선해 덮어쓴다.

```sh
./gradlew bootRun --args="--remittance.aes-key=$(openssl rand -base64 32) --remittance.jwt-secret=$(openssl rand -base64 32)"
```

`--remittance.usd-to-krw=1350`처럼 다른 설정도 같은 방식으로 지정한다. 환경변수 없이 이 방식만으로
기동된다. 예시는 실행마다 새 키를 만들므로 `./data/remittance`에 기존 데이터가 있으면 이전과 같은 AES 키를
직접 지정해야 한다. 인자로 준 키는 `ps` 같은 프로세스 목록과 셸 히스토리에 남으므로
로컬 개발용 대안으로만 쓰고, 공유 환경이나 운영에서는 환경변수(또는 비밀 관리 도구)를 사용한다.
`./gradlew -Dremittance.aes-key=...`는 Gradle 자신의 JVM 프로퍼티라 앱에 전달되지 않는다.
환경변수 누락·잘못된 키는 시작 시 거부한다. H2 파일은 `./data/remittance`에 저장된다.
테스트는 별도 메모리 H2와 테스트 전용 키·환율·시간을 사용한다.

환율 기본 설정은 재현 가능한 예시 USD→KRW 1300, JPY→KRW 9다. 실제 시장 환율이 아니다.
`REMITTANCE_USD_TO_KRW`, `REMITTANCE_JPY_TO_KRW`로 값을 공급할 수 있다.
외부 시세 제공처가 정해지면 환율 포트의 어댑터를 교체한다.

## API

모든 성공·오류 응답은 JSON이다. 본문이 있는 요청은 `Content-Type: application/json`,
송금 API는 로그인 응답의 `Authorization: Bearer <token>`을 사용한다.

| API | 입력 | 결과 |
| --- | --- | --- |
| POST /user/signup | userId(email), password, name, idType, idValue | resultCode, resultMsg |
| POST /user/login | userId, password | 기본 응답 + token |
| POST /transfer/quote | amount(KRW), targetCurrency(USD/JPY) | 기본 응답 + quote |
| POST /transfer/request | quoteId(문자열), 선택 헤더 Idempotency-Key | 기본 응답 |
| GET /transfer/list | 선택 size, cursor | 기본 응답 + 사용자, 오늘 집계, 접수 이력 한 페이지, nextCursor |

성공 응답의 `resultCode=200`, `resultMsg=OK`. JWT는 id/userId/name/expireTime 및
표준 인증 클레임을 포함하고 발급 후 30분 만료한다. 견적은 10분 유효하다.
API 날짜 표시는 KST `yyyy-MM-dd HH:mm:ss`, 저장은 UTC Instant다.
원문 만료 오류의 철자 `QUOTE_EXPRIED`를 계약으로 유지한다.

오류의 `resultCode`는 HTTP 상태와 같으며 `resultMsg`는 고정 코드다.

| HTTP | 대표 오류 |
| --- | --- |
| 400 | INVALID_REQUEST, NEGATIVE_NUMBER, QUOTE_EXPRIED, LIMIT_EXCESS |
| 401 | UNAUTHORIZED |
| 403 | FORBIDDEN(타인 견적) |
| 404 | QUOTE_NOT_FOUND, NOT_FOUND |
| 409 | DUPLICATE_USER, ALREADY_REQUESTED |
| 422 | IDEMPOTENCY_KEY_REUSED(같은 키를 다른 견적에 사용) |
| 503 | LOCK_TIMEOUT(사용자 잠금 대기 초과, 재시도 가능) |
| 405 / 406 / 415 | METHOD_NOT_ALLOWED / NOT_ACCEPTABLE / UNSUPPORTED_MEDIA_TYPE |
| 500 | UNKNOWN_ERROR(내부 정보 미노출) |

비밀번호는 UTF-8 최대 72바이트(BCrypt 제한), 식별번호는 최대 64바이트(AES 저장 길이)다.
견적 ID는 양의 INT 범위를 받고, 원화 입력은 정수부 최대 10자리·소수 최대 12자리다.

```sh
curl -X POST http://localhost:8080/user/signup \
  -H 'Content-Type: application/json' \
  -d '{"userId":"demo@example.com","password":"example-password","name":"테스트 사용자","idType":"REG_NO","idValue":"example-identity"}'
curl -X POST http://localhost:8080/user/login \
  -H 'Content-Type: application/json' \
  -d '{"userId":"demo@example.com","password":"example-password"}'
curl -X POST http://localhost:8080/transfer/quote \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer <token>' \
  -d '{"amount":400000,"targetCurrency":"USD"}'
```

### Swagger UI

실행 후 `http://localhost:8080/swagger-ui.html`에서 API를 확인하고 호출할 수 있다.
OpenAPI 명세(JSON)는 `/v3/api-docs`다. 송금 API는 로그인 응답의 `token`을 UI의 Authorize에
입력한다. 명세는 코드의 어노테이션에서 생성되며 인증 없이 열린다. 외부에 노출하지 않을 환경에서는
`REMITTANCE_API_DOCS_ENABLED=false`로 UI와 명세를 함께 끈다.

## 계산과 한도

- BigDecimal과 HALF_UP를 사용한다. KRW/JPY 0자리, USD 2자리는 Java Currency 기준이다.
- USD 100만원 이하: 고정 1000원 + 0.002, 초과: 고정 3000원 + 0.001.
- JPY: 고정 3000원 + 0.05. ratedFee를 원화로 반올림한 후 고정 수수료를 더한다.
- 수취금액=(보내는금액-수수료)/수취통화→KRW 환율. 일일 한도도 순송금 USD 금액으로 계산한다.
- 견적 생성 시 환율·수수료를 고정한다. REG_NO는 KST 하루 1000 USD, BUSINESS_NO는 5000 USD.
- 같은 사용자 DB 행 잠금으로 한도 집계와 접수를 원자적으로 처리한다. 같은 견적은 한 번만 접수한다.
- 소유권을 검사하며 만료 순간도 거부한다. 이력은 접수시간·ID 내림차순이고 견적만 만든 건 제외한다.

## 구조

기본 패키지는 개인 프로젝트 이름 공간인 `com.nelson.remittance`다.

```text
domain                         업무 모델·금액 정책 (프레임워크 없음)
application                    사용 사례·외부 포트
adapter/web      JSON HTTP와 인증 사용자 변환
adapter/persistence            JPA 모델·저장소·트랜잭션
adapter/security, adapter/forex 암호화·토큰·환율 공급
bootstrap                      Spring 조립·시간 공급
```

외부 기술이 내부 정책을 의존한다. JPA 객체나 HTTP DTO를 내부 모델로 전달하지 않는다.
한글 주석은 금액·한도·잠금·암호화의 불변식과 설계 이유를 설명한다.

## 요구사항과 검증 연결

| 요구사항 | 구현 | 검증 |
| --- | --- | --- |
| 회원가입·로그인·5개 JSON API | application 사용 사례, web/security 어댑터 | RemittanceApiTest |
| BCrypt·AES-GCM·30분 JWT | 보안 어댑터, 환경변수 키 | SecurityAdaptersTest, DB 저장/API 테스트 |
| 수수료 구간·반올림·환율 방향 | 프레임워크 없는 QuotePolicy | QuotePolicyTest |
| 10분 만료·소유권·중복 | Quote와 접수 사용 사례 | API 만료/타인/중복 테스트 |
| 개인/법인 한도·KST 오늘 집계 | 견적 USD + 사용자 DB 잠금 | 정확한 한도·자정·동시 접수 테스트 |
| H2 순차 ID·FK·인덱스·DECIMAL | 외부 JPA 모델 | 실제 H2 스키마/감사 값 테스트 |
| 클린 아키텍처·한글 주석 | domain/application/adapter/bootstrap | ArchitectureTest + 내부 리뷰 |

## 검증과 회고

```sh
./gradlew spotlessApply
./gradlew clean check bootJar --no-daemon
python3 -m unittest discover -s scripts/tests -v
```

`spotlessApply`는 Java 코드를 자동 정리한다. `check`는 main/test 전체의 포맷과
Checkstyle 규칙을 검사하고 위반 시 실패한다. 하네스 커밋 게이트도 같은 명령을 사용한다.

Java 17에서 전체 애플리케이션 테스트, Spotless 포맷 검사와 Checkstyle main/test 검사를
통과했고 실행 JAR도 빌드했다. 커밋 게이트는 `clean check bootJar`, 하네스 회귀 검사와
독립 리뷰를 통과한 최신 snapshot만 커밋한다.

포맷은 Google Java Format 1.22.0 AOSP(4칸)를 Spotless 6.25.0으로 적용한다.
Checkstyle 10.17.0은 wildcard·불필요 import, 중괄호, 제어 흐름과 명명 규칙을 검사한다.
설정은 `build.gradle`, `config/checkstyle/checkstyle.xml`, `.editorconfig`에 있다.

검증 범위는 금액·수수료·정밀도, 30분 JWT와 암호화, 10분 견적 만료, KST 날짜 변경,
개인/법인 정확한 한도와 초과, 타인 견적, 중복/동시 접수, 동시 회원가입, 실제 Tomcat의
JSON 오류 경로, H2 스키마/감사 데이터와 내부 계층 의존성이다. 실제 실행 로그는
Git에서 제외되는 `.harness/check-*.log`에 남고 PR 본문에는 최종 결과를 요약한다.
기존 홈 파일 DB/외부 환율 의존 테스트는 기준선으로 실행하지 않았다.
새 테스트는 도메인, 보안 어댑터, JSON API, H2 영속성·동시성, 계층 의존성을 검증한다.

목표 문서의 일부 예시와 명시 규칙은 충돌한다. JPY의 소수 예시보다 Currency의
0자리 규칙, 환율 표기보다 명시된 나눗셈 공식을 우선했다. 과도한 저장 자릿수를
명시적으로 거부하고, 견적별 반올림된 USD를 합산하여 한도 정책을 재현 가능하게 했다.

현재 환율은 설정 기반 예시이며 실시간 시세 연동은 미구현이다. embedded H2와 DB 잠금은
과제 조건의 구현이며 다른 운영 DB에서의 잠금·장애 복구 검증을 대신하지 않는다.
ORM 유일 제약 오류에 이메일이 포함될 수 있어 SqlExceptionHelper의
원시 오류 로그를 차단했다. 이로 인해 DB 진단 상세가 줄어들며, 운영에서는 개인정보를
제거한 오류 관측 체계를 별도로 설계해야 한다.
데이터 마이그레이션과 실제 금융기관 송금 실행은 제공하지 않는다. 요청 API는 접수 기록을 생성한다.
실제 배포·CI/CD는 구성하지 않는다. dev 대상 PR의 승인·병합은 사람이 수행한다.

에이전트 실행은 [AGENTS.md](AGENTS.md), 옵시디언 연결은 [문서화 사용법](docs/harness/obsidian.md)을 따른다.
