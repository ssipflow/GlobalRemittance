# GlobalRemittance

원화로 견적을 만들고 USD 또는 JPY 송금을 접수하는 개인 프로젝트다. 정확한 금액 계산, 중복·동시 요청에서도 깨지지 않는 한도 검사, 설명할 수 있는 설계를 목표로 한다. 기본 패키지는 `com.nelson.remittance`다.

## 한눈에 보기

| 단계 | API | 하는 일 |
| --- | --- | --- |
| 1. 가입 | `POST /user/signup` | 이메일 계정 생성. 비밀번호는 해시, 식별번호는 암호화해 저장 |
| 2. 로그인 | `POST /user/login` | JWT 발급(30분) |
| 3. 견적 | `POST /transfer/quote` | 환율·수수료를 확정한 10분짜리 견적 저장 |
| 4. 접수 | `POST /transfer/request` | 견적을 접수 기록으로 전환. 소유권·중복·만료·일일 한도 검사 |
| 5. 이력 | `GET /transfer/list` | 접수 이력을 페이지로 조회하고 KST 오늘 집계 제공 |

접수는 실제 금융기관으로 자금을 보내는 것이 아니라 견적을 접수 기록으로 바꾸는 것이다. 요청·응답 형식은 [[API 문서]]에 있다.

## 문서 읽는 순서

1. [[API 문서]]: 무엇을 호출할 수 있고 어떤 응답과 오류가 오는지.
2. [[아키텍처]]: 코드가 계층으로 어떻게 나뉘고 요청이 어떻게 흐르는지, 데이터가 어디에 어떻게 저장되는지.
3. [[주요 설계]]: 금액 계산, 동시성, 시간, 보안 등 각 선택의 이유와 치른 대가.

## 기술과 범위

- Java 17, Spring Boot 3, Spring Security, JPA, embedded H2.
- 금액은 BigDecimal, 시각은 UTC Instant로 저장하고 API에서는 KST로 보여 준다.
- 테스트는 컨텍스트마다 분리된 메모리 H2와 고정 시간·환율로 격리한다.

범위 밖이다: 실제 금융기관 송금, 실시간 환율 공급(지금은 설정값), 운영 DB에서의 잠금·장애 복구 검증, 기존 데이터 마이그레이션.

## 실행 설정

| 환경변수 | 용도 | 기본값 |
| --- | --- | --- |
| REMITTANCE_AES_KEY | Base64로 인코딩한 32바이트 AES 키 | 필수 |
| REMITTANCE_JWT_SECRET | Base64로 인코딩한 32바이트 이상 JWT 키 | 필수 |
| REMITTANCE_USD_TO_KRW | USD 1단위당 KRW | 1300 (예시) |
| REMITTANCE_JPY_TO_KRW | JPY 1단위당 KRW | 9 (예시) |
| REMITTANCE_LOCK_TIMEOUT_MS | 같은 사용자의 접수 잠금을 기다리는 최대 시간(ms) | 5000 |
| REMITTANCE_DB_PASSWORD | 로컬 H2 비밀번호 | 빈 값 |

`./gradlew bootRun`으로 실행하며 파일 DB는 `./data/remittance`다. 이미 저장된 식별번호를 복호화하려면 같은 AES 키를 유지해야 한다. 키와 실제 개인정보는 문서나 저장소에 기록하지 않는다.
