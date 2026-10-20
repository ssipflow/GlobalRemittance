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

- [API 문서](api.md): 인증 방법, 필드 제약, 요청·응답 예시와 오류 코드.
- [아키텍처](architecture.md): 계층별 책임, 요청 처리 순서와 저장 모델.
- [주요 설계](design.md): 금액·시간·페이지네이션·스키마 변경의 선택과 비용.
- [동시성과 멱등성](concurrency.md): 사용자 잠금·재시도·DB 장애 실험.
- [Observability](observability.md): 요청 ID·오류 로그·기동 진단.
- [프로젝트 안내](../portfolio/index.md): AX 목적과 대표 사례·추천 읽기 순서.

## 구현 환경

Java 17, Spring Boot 3, Spring Security, JPA, Flyway, PostgreSQL을 사용한다. 기본 실행은 PostgreSQL이고 로컬 DB는 `docker-compose.yml`(PostgreSQL 16)로 띄운다. embedded H2는 `h2` 프로파일로 실행하며 기존 `./data` 파일 DB는 그대로 보존한다. 스키마는 Flyway SQL 마이그레이션으로 만들고 Hibernate는 검증만 한다. 기본 패키지는 `com.nelson.remittance`다. 금액은 BigDecimal로 계산하고 시각은 UTC Instant로 저장한다. API의 견적·이력 시각은 KST로 표시한다.

테스트는 컨텍스트마다 분리된 메모리 H2와 고정 Clock·환율을 사용한다. 외부 환율 서비스나 사용자 홈 DB 없이 금액·만료·한도·동시 접수·페이지 경계를 재현한다.

## 실행 설정

필수 설정은 Base64 32바이트 AES 키(`REMITTANCE_AES_KEY`), Base64 32바이트 이상 JWT 키(`REMITTANCE_JWT_SECRET`), 기본 PostgreSQL의 DB 비밀번호(`REMITTANCE_DB_PASSWORD`)다. 개발 키 생성·Docker DB·프로파일·타임아웃 등 전체 설정은 [README 실행 안내](../../README.md#먼저-실행하기)와 [로컬 실행 설정](local-guide.md#설정)를 따른다.

키와 DB를 준비한 뒤 `./gradlew bootRun`으로 실행한다. `./data/remittance` 파일 DB는 `h2` 또는 H2 기반 `local` 프로파일에서만 사용한다. 기본 PostgreSQL 실행은 이 파일을 만들지 않는다. 기존 암호화 식별번호를 읽으려면 AES 키를 유지해야 한다. 비밀 값과 개인정보는 저장소·문서에 기록하지 않는다.

접수 API는 선택적 `Idempotency-Key`로 같은 견적의 성공 응답을 접수 후 24시간 미만 동안 재현한다. 키는 사용자별로 견적에 계속 귀속되며 실패 응답은 저장하지 않는다. PostgreSQL 16에서 같은 JVM의 독립 앱 컨텍스트 2개를 통한 분산 동시 접수, DB 재시작·강제 종료·무응답, 프록시 네트워크 장애와 COMMIT 전·후 응답 유실을 검증했다. 별도 프로세스·호스트의 운영 검증은 아니며 실제 금융기관 송금, 실제 시세 공급처와 다른 시스템에서의 데이터 이전은 구현하지 않았다. 상세 구성과 한계는 [검증 범위](../portfolio/validation.md)를 따른다.

## 실행 후 확인할 경로와 헤더

Swagger UI는 `/swagger-ui.html`, OpenAPI JSON은 `/v3/api-docs`다. 로그인 응답의 token을 Authorize에 넣어 송금 API를 호출한다. 명세와 UI는 기본적으로 인증 없이 공개되며 설정으로 함께 끌 수 있다.

local 프로파일의 `/h2-console`은 앱 JWT를 사용하지 않고 DB 로그인으로 접속한다. SQL·DB 오류 로그도 이 프로파일에서 확장되므로 로컬 테스트 데이터에서만 사용한다. 기본 프로파일은 콘솔을 끄고 원시 SQL 오류 로그를 제한한다.

응답의 X-Request-Id는 서버 로그를 찾는 값이고, Idempotency-Key는 접수 재시도를 구분하는 값이다. LOCK_TIMEOUT·DB_UNAVAILABLE의 Retry-After: 1은 고정 1초 뒤 재시도 안내이며 자동 재시도·성공 보장은 아니다.

실행 인자, DB 비밀번호 유지, 로컬 진단과 마이그레이션 절차는 [로컬 실행 가이드](local-guide.md)에 모았다. 스키마는 V1~V3 SQL과 Flyway 이력으로 관리하며 임의의 외부 DB를 자동으로 복구하거나 이전하지 않는다.
