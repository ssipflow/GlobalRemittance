# GlobalRemittance

개인 프로젝트의 송금 접수 서비스다. 회원가입과 로그인 후 원화로 견적을 만들고 USD 또는 JPY 송금을 접수하며 이력을 조회한다. 기본 패키지는 `com.nelson.remittance`다.

## 문서 안내

- [[아키텍처]]: 계층의 책임, 의존성 방향, 요청 처리와 저장 구조.
- [[API 문서]]: 인증, 요청·응답 스키마, 예시와 오류 계약.
- [[주요 설계]]: 금액·수수료, 일일 한도, 동시성, 만료와 보안의 선택 근거.

## 기술과 범위

Java 17, Spring Boot 3, Spring Security, JPA, embedded H2를 사용한다. 금액은 BigDecimal, 날짜 저장은 UTC Instant, API 표시는 KST를 사용한다. 테스트는 별도 메모리 H2와 고정 시간·환율로 격리한다.

실제 금융기관으로 자금을 보내는 시스템은 아니다. 송금 요청 API는 견적을 접수 기록으로 전환한다. 환율은 설정 기반 예시이고 실시간 시세 공급, 운영 DB 검증, 기존 데이터 마이그레이션은 별도 과제다.

## 실행 설정

| 환경변수 | 용도 |
| --- | --- |
| REMITTANCE_AES_KEY | Base64로 인코딩한 32바이트 AES 키, 필수 |
| REMITTANCE_JWT_SECRET | Base64로 인코딩한 32바이트 이상 JWT 키, 필수 |
| REMITTANCE_USD_TO_KRW | USD 1단위당 KRW, 기본 예시 1300 |
| REMITTANCE_JPY_TO_KRW | JPY 1단위당 KRW, 기본 예시 9 |
| REMITTANCE_DB_PASSWORD | 로컬 H2 비밀번호, 기본 빈 값 |

`./gradlew bootRun`으로 실행한다. 파일 DB는 `./data/remittance`다. 이미 저장된 식별번호를 복호화하려면 같은 AES 키를 유지해야 한다. 키와 실제 개인정보를 문서나 저장소에 기록하지 않는다.
