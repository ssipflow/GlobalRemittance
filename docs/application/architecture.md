# 아키텍처

## 의존성 방향

기본 패키지 `com.nelson.remittance`에서 내부 정책은 Spring·JPA·HTTP 구현에 의존하지 않는다. 외부 어댑터는 application 포트를 구현하거나 사용 사례를 호출하고 bootstrap은 객체를 조립한다.

```mermaid
flowchart LR
    W[adapter/web] --> A[application]
    P[adapter/persistence] --> A
    S[adapter/security] --> A
    F[adapter/forex] --> A
    A --> D[domain]
    B[bootstrap] --> A
    B --> P
    B --> S
    B --> F
```

| 계층 | 책임 | 대표 클래스 |
| --- | --- | --- |
| domain | 금액·수수료 계산, 견적 상태와 만료, 개인/법인 한도 | QuotePolicy, Quote, User, BusinessException |
| application | 사용 사례 순서, 시간 경계와 트랜잭션 포트 | UserUseCases, TransferUseCases, Ports |
| adapter/web | 입력 검증, 인증 사용자 변환, JSON 응답과 오류 | UserController, TransferController, ApiModels, ApiExceptionHandler, JsonErrorController |
| adapter/persistence | JPA 모델 변환, 저장과 DB 잠금, 트랜잭션 실행 | JpaStore, UserEntity, QuoteEntity, TransactionalWork |
| adapter/security | BCrypt 연결, AES-GCM, JWT 발급·검증 | AesIdentityCipher, JwtTokens, JwtAuthenticationFilter, SecurityConfiguration |
| adapter/forex | 설정 환율 공급 | ConfiguredRates |
| bootstrap | 의존성 주입과 Clock 구성 | ApplicationConfiguration |

웹 DTO와 JPA entity를 domain으로 넘기지 않는다. Ports는 Accounts, Quotes, Rates, Passwords, IdentityCipher, Tokens, Work 등 실제 기술 경계다. Work 구현은 TransactionTemplate을 사용해 Spring 트랜잭션 의존성을 application 밖에 둔다.

## 송금 접수 흐름

```mermaid
sequenceDiagram
    participant C as 클라이언트
    participant W as 인증/Web
    participant A as TransferUseCases
    participant P as JpaStore/H2
    C->>W: POST /transfer/request + Bearer JWT
    W->>A: request(actorId, quoteId)
    A->>P: 트랜잭션 시작 + 사용자 행 잠금
    A->>P: 견적 및 사용자 접수 이력 조회
    A->>A: 소유권·중복·만료·KST 일일 한도 검사
    A->>P: requestedTime 저장 + 커밋
    A-->>W: 완료
    W-->>C: JSON 200 OK
```

견적 생성 시 수수료·환율·USD 환산액을 확정한다. 접수는 당시 값을 재사용한다. 동일 사용자 행 잠금으로 서로 다른 견적의 동시 접수도 직렬화한다. 이력 조회도 같은 사용자 잠금 안에서 목록과 오늘 합계를 일관되게 만든다.

## 저장 모델

- users: 순차 INT 식별자, 유일한 userId, BCrypt 비밀번호, 암호화 식별번호, 신원 유형, 생성/수정 시각.
- remittances: 사용자 FK와 인덱스, 순차 INT 식별자, DECIMAL(22,12) 금액·환율·수수료 감사 값, 만료·생성·수정·접수 시각.
- requestedTime이 null이면 미접수 견적이다. 접수 이력에는 requestedTime이 있는 항목만 포함한다.
- 도메인 식별자는 long이지만 영속성의 INT 범위로 변환·검증한다. UTC 저장과 KST API 표시를 분리한다.

## 검증과 한계

ArchitectureTest는 컴파일된 domain/application의 프레임워크·어댑터 의존성을 검사한다. API 통합 테스트는 실제 H2, 인증, 동시 접수 및 Tomcat 오류 디스패치를 검사한다.

현재 전체 이력을 읽어 집계한다. 거래량이 커지면 기간별 DB 집계와 페이지네이션이 필요하다. H2 잠금 검증은 다른 운영 DB에서의 동작·장애 복구를 보증하지 않는다.
