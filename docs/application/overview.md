# GlobalRemittance

원화 송금 견적을 만들고 USD 또는 JPY 송금을 접수하는 개인 프로젝트다. 회원가입·로그인·견적·접수·이력 조회를 제공하며, 금액 계산과 동시 요청에서의 일일 한도 검증을 주요 설계 대상으로 삼는다.

송금 접수는 DB에 접수 시각을 기록하는 단계까지 구현했다. 금융기관에 지급을 요청하거나 실제 자금을 이동하지는 않는다. 환율도 외부 시세가 아닌 설정값을 사용한다.

## 서비스 흐름

| 단계 | API | 처리 내용 |
| --- | --- | --- |
| 회원가입 | `POST /user/signup` | 이메일 계정을 만들고 비밀번호 해시·암호화 식별번호 저장 |
| 로그인 | `POST /user/login` | 30분 유효한 JWT 발급 |
| 견적 | `POST /transfer/quote` | 환율·수수료·수취액을 확정하고 10분 유효한 견적 저장 |
| 접수 | `POST /transfer/request` | 소유권·중복·만료·일일 한도 검사 후 접수 시각 기록 |
| 이력 | `GET /transfer/list` | 접수 이력의 한 페이지와 KST 오늘 건수·USD 합계 반환 |

견적을 생성해도 일일 한도에는 반영되지 않는다. 접수한 견적의 순송금 USD 금액을 합산해 개인은 하루 1000 USD, 법인은 5000 USD까지 허용한다. 같은 사용자의 접수가 겹치면 DB 행 잠금으로 한 번에 하나씩 처리한다.

## 문서 안내

- [[API 문서]]: 인증 방법, 필드 제약, 요청·응답 예시와 오류 코드.
- [[아키텍처]]: 계층별 책임, 요청 처리 순서와 저장 모델.
- [[주요 설계]]: 금액·시간·동시성·페이지네이션의 선택 근거와 검증 범위.

## 구현 환경

Java 17, Spring Boot 3, Spring Security, JPA, Flyway, embedded H2를 사용한다. 스키마는 Flyway SQL 마이그레이션으로 만들고 Hibernate는 검증만 한다. 기본 패키지는 `com.nelson.remittance`다. 금액은 BigDecimal로 계산하고 시각은 UTC Instant로 저장한다. API의 견적·이력 시각은 KST로 표시한다.

테스트는 컨텍스트마다 분리된 메모리 H2와 고정 Clock·환율을 사용한다. 외부 환율 서비스나 사용자 홈 DB 없이 금액·만료·한도·동시 접수·페이지 경계를 재현한다.

## 실행 설정

| 환경변수 | 용도 | 기본값 |
| --- | --- | --- |
| REMITTANCE_AES_KEY | Base64로 인코딩한 32바이트 AES 키 | 필수 |
| REMITTANCE_JWT_SECRET | Base64로 인코딩한 32바이트 이상 JWT 키 | 필수 |
| REMITTANCE_USD_TO_KRW | USD 1단위당 KRW | 1300, 예시 환율 |
| REMITTANCE_JPY_TO_KRW | JPY 1단위당 KRW | 9, 예시 환율 |
| REMITTANCE_API_DOCS_ENABLED | Swagger UI(`/swagger-ui.html`)와 OpenAPI 명세(`/v3/api-docs`) 노출 | true |
| REMITTANCE_LOCK_TIMEOUT_MS | H2 잠금 대기 한도(ms). 1~30000 정수가 아니면 기동을 거부한다 | 5000 |
| REMITTANCE_DB_PASSWORD | 로컬 H2 `sa` 비밀번호. DB 파일을 처음 만들 때 고정되며 DB와 다른 값으로 실행하면 기동이 거부된다 | 빈 값 |
| SPRING_PROFILES_ACTIVE | `local`이면 H2 콘솔(`/h2-console`)과 SQL·DB 오류 로그를 켠다. 로컬 개발 전용 | 없음(꺼짐) |

필수 키를 설정한 뒤 `./gradlew bootRun`으로 실행한다. 파일 DB는 `./data/remittance`에 생성된다. 암호화한 식별번호를 읽으려면 기존 AES 키를 유지해야 한다. 실제 키와 개인정보는 저장소나 문서에 넣지 않는다.

접수 API는 선택적 `Idempotency-Key`로 재시도를 안전하게 한다(성공 응답을 24시간 재현). 운영 DB의 잠금·장애 복구, 실제 금융기관 송금, 실시간 환율 공급과 다른 시스템에서의 데이터 이전은 구현·검증 범위에 포함하지 않았다.
