# GlobalRemittance

원화 송금 견적을 만들고 USD 또는 JPY 송금을 접수하는 개인 프로젝트다. 회원가입·로그인·견적·접수·이력 조회를 제공하며, 금액 계산과 동시 요청에서의 일일 한도 검증을 주요 설계 대상으로 삼는다.

송금 접수는 DB에 접수 시각을 기록하는 단계까지 구현했다. 금융기관에 지급을 요청하거나 실제 자금을 이동하지는 않는다. 환율도 외부 시세가 아닌 설정값을 사용하지만, 환율에 기준 시각을 붙여 오래됐거나 조회에 실패하면 견적을 거부하는 신선도 정책을 갖췄다(지연·장애를 주입하는 모의 공급처로 검증).

## 서비스 흐름

| 단계 | API | 처리 내용 |
| --- | --- | --- |
| 회원가입 | `POST /user/signup` | 이메일 계정을 만들고 비밀번호 해시·암호화 식별번호 저장 |
| 로그인 | `POST /user/login` | 30분 유효한 JWT 발급 |
| 견적 | `POST /transfer/quote` | 환율·수수료·수취액을 확정하고 10분 유효한 견적 저장 |
| 접수 | `POST /transfer/request` | 소유권·중복·만료·일일 한도 검사 후 접수 시각 기록 |
| 이력 | `GET /transfer/list` | 접수 이력의 한 페이지와 KST 오늘 건수·USD 합계 반환 |

최소 송금 금액은 5000 KRW이고, 이메일 계정은 대소문자를 구분하지 않는다. 견적을 생성해도 일일 한도에는 반영되지 않는다. 접수한 견적의 순송금 USD 금액을 합산해 개인은 하루 1000 USD, 법인은 5000 USD까지 허용한다. 같은 사용자의 접수가 겹치면 DB 행 잠금으로 한 번에 하나씩 처리한다.

## 문서 안내

- [[API 문서]]: 인증 방법, 필드 제약, 요청·응답 예시와 오류 코드.
- [[아키텍처]]: 계층별 책임, 요청 처리 순서와 저장 모델.
- [[주요 설계]]: 상세 동시성·멱등성·페이지네이션·오류 관측·스키마 변경의 근거와 검증 범위.

## 구현 환경

Java 17, Spring Boot 3, Spring Security, JPA, Flyway, PostgreSQL을 사용한다. 기본 실행은 PostgreSQL이고 로컬 DB는 `docker-compose.yml`(PostgreSQL 16)로 띄운다. embedded H2는 `h2` 프로파일로 실행하며 기존 `./data` 파일 DB는 그대로 보존한다. 스키마는 Flyway SQL 마이그레이션으로 만들고 Hibernate는 검증만 한다. 기본 패키지는 `com.nelson.remittance`다. 금액은 BigDecimal로 계산하고 시각은 UTC Instant로 저장한다. API의 견적·이력 시각은 KST로 표시한다.

테스트는 컨텍스트마다 분리된 메모리 H2와 고정 Clock·환율을 사용한다. 외부 환율 서비스나 사용자 홈 DB 없이 금액·만료·한도·동시 접수·페이지 경계를 재현한다.

## 실행 설정

| 환경변수 | 용도 | 기본값 |
| --- | --- | --- |
| REMITTANCE_AES_KEY | Base64로 인코딩한 32바이트 AES 키 | 필수 |
| REMITTANCE_JWT_SECRET | Base64로 인코딩한 32바이트 이상 JWT 키 | 필수 |
| REMITTANCE_USD_TO_KRW | USD 1단위당 KRW | 1300, 예시 환율 |
| REMITTANCE_JPY_TO_KRW | JPY 1단위당 KRW | 9, 예시 환율 |
| REMITTANCE_RATES_MAX_AGE_SECONDS | 환율 기준 시각 허용 지연(초, 1~3600). 넘으면 견적 503 | 60 |
| REMITTANCE_RATES_SOURCE | `configured` 또는 `simulated`(모의 공급처, 지연·장애 주입, 운영 사용 금지) | configured |
| REMITTANCE_RATES_SIMULATED_AGE_SECONDS / _UNAVAILABLE | 모의 공급처의 지연(초)과 장애 여부 | 0 / false |
| REMITTANCE_API_DOCS_ENABLED | Swagger UI(`/swagger-ui.html`)와 OpenAPI 명세(`/v3/api-docs`) 노출 | true |
| REMITTANCE_LOCK_TIMEOUT_MS | 사용자 행 잠금 대기 한도(ms). H2는 URL `LOCK_TIMEOUT`, `postgres` 프로파일은 연결별 `SET lock_timeout`으로 적용한다. 1~30000 ASCII 숫자, 부호·공백·선행 0 불가 | 5000 |
| REMITTANCE_DB_PASSWORD | DB 접속 비밀번호. 기본(PostgreSQL)에서는 필수이며 compose의 `POSTGRES_PASSWORD`와 같은 값이다. H2는 `sa` 비밀번호이며 파일 DB를 처음 만들 때 고정되어 다른 값으로 실행하면 기동이 거부된다. `postgres` 프로파일은 PostgreSQL 비밀번호 | 빈 값(PostgreSQL은 필수) |
| REMITTANCE_DB_URL / REMITTANCE_DB_USER | `postgres` 프로파일의 JDBC URL과 사용자 | `jdbc:postgresql://localhost:5432/remittance` / `remittance` |
| SPRING_PROFILES_ACTIVE | 지정하지 않으면 `postgres`(PostgreSQL과 전용 마이그레이션). 지정하면 기본값이 대체되어 `h2`는 H2 파일 DB, `local`은 H2 콘솔(`/h2-console`)과 SQL·DB 오류 로그를 켠 H2 실행이다(로컬 개발 전용). PostgreSQL에서 진단 로그를 쓰려면 `postgres,local` | 없음(`postgres`, 콘솔 꺼짐) |

필수 키를 설정한 뒤 `./gradlew bootRun`으로 실행한다. 파일 DB는 `./data/remittance`에 생성된다. 암호화한 식별번호를 읽으려면 기존 AES 키를 유지해야 한다. 실제 키와 개인정보는 저장소나 문서에 넣지 않는다.

접수 API는 선택적 `Idempotency-Key`로 같은 견적의 성공 응답을 접수 후 24시간 미만 동안 재현한다. 키는 사용자별로 견적에 계속 귀속되며 실패 응답은 저장하지 않는다. PostgreSQL 16에서 같은 JVM의 독립 앱 컨텍스트 2개를 통한 분산 동시 접수, DB 재시작·강제 종료·무응답, 프록시 네트워크 장애와 COMMIT 전·후 응답 유실을 검증했다. 별도 프로세스·호스트의 운영 검증은 아니며 실제 금융기관 송금, 실제 시세 공급처와 다른 시스템에서의 데이터 이전은 구현하지 않았다. 상세 구성과 한계는 [검증 범위](../portfolio/validation.md)를 따른다.

## 실행 후 확인할 경로와 헤더

Swagger UI는 `/swagger-ui.html`, OpenAPI JSON은 `/v3/api-docs`다. 로그인 응답의 token을 Authorize에 넣어 송금 API를 호출한다. 명세와 UI는 기본적으로 인증 없이 공개되며 설정으로 함께 끌 수 있다.

local 프로파일의 `/h2-console`은 앱 JWT를 사용하지 않고 DB 로그인으로 접속한다. SQL·DB 오류 로그도 이 프로파일에서 확장되므로 로컬 테스트 데이터에서만 사용한다. 기본 프로파일은 콘솔을 끄고 원시 SQL 오류 로그를 제한한다.

응답의 X-Request-Id는 서버 로그를 찾는 값이고, Idempotency-Key는 접수 재시도를 구분하는 값이다. LOCK_TIMEOUT의 Retry-After: 1은 고정 1초 뒤 재시도 안내이며 자동 재시도·성공 보장은 아니다.

실행 인자, DB 비밀번호 유지, 로컬 진단과 마이그레이션 절차는 저장소 README에 모았다. 스키마는 V1~V3 SQL과 Flyway 이력으로 관리하며 임의의 외부 DB를 자동으로 복구하거나 이전하지 않는다.
