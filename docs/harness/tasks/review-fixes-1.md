# review-fixes-1

- 배경: 코드 리뷰(읽기 전용, 미검증)의 지적 3건을 재현 테스트로 먼저 확인하고 결함일 때만 고쳤다.

## 1. 0.00 금액 견적
- 요구사항 근거: `project-goals.md`는 "음수 입력/음수 수취금액은 NEGATIVE_NUMBER"만 정한다. 0 이하 입력은 이미 거부(`amount.signum() <= 0`), 순송금 0은 허용이 기존 테스트(`zeroNetOrRoundedZeroIsAllowedButNegativeIsNot`)로 고정돼 있었다. 최소 금액은 어디에도 없다.
- 관찰: 1003 KRW → USD(환율 1300, 수수료 1000+2)는 순송금 1원, 수취·USD `0.00`으로 발급되고 접수까지 200이다(`roundedZeroUsdQuoteIsIssuedAndRequestedUnderCurrentPolicy`, `netOneWonRoundsToZeroReceivedButIsNotRejected`).
- 판단: 명세 위반이 아니라 정책 공백이다. 거절하려면 새 오류 코드 또는 NEGATIVE_NUMBER(의미 확장) 선택과 최소 금액 정의가 필요한데 요구사항에 근거가 없어 **코드는 바꾸지 않았다**. 현재 동작을 고정하는 테스트와 api.md 설명만 추가. 정책이 정해지면 `QuotePolicy`에서 `received/usd.signum() <= 0` 검사를 추가하고 테스트를 뒤집는다.

## 2. 견적 저장 잠금 타임아웃
- 재현(PostgreSQL 16, `PostgresQuoteLockTimeoutTest`): 외부 JDBC 연결이 사용자 행을 FOR UPDATE로 쥔 채 POST /transfer/quote → 수정 전 `500 {"resultMsg":"UNKNOWN_ERROR"}`. 결함 확인.
- 원인: 견적 INSERT의 `remittances.user_id` 외래키 검사가 사용자 행 공유 잠금을 요청해 lock_timeout(55P03)에 걸리는데 변환이 `lockUser`에만 있었다.
- 수정: `JpaQuoteRepository.saveQuote`의 persist/flush(및 접수 flush)에서 `PersistenceErrors.isLockTimeout`이면 `BusinessException(LOCK_TIMEOUT)`(한 헬퍼 `translateLockTimeout`). 핸들러가 기존대로 503 + `Retry-After: 1`을 만든다.
- 수정 후 관찰: 503 LOCK_TIMEOUT, Retry-After 1, 경과 2000~5000ms, 견적 행 0건, 잠금 해제 후 같은 요청 200과 행 1건. H2 기존 테스트 통과.

## 3. 가입·로그인 트랜잭션 안의 BCrypt
- 재현(H2, `loginDoesNotHoldDbConnectionWhileHashingPassword`, Hikari MXBean 표본 추출): 수정 전 loginMs=291, 연속 연결 점유 289ms. 결함 확인. 수정 후 loginMs=292, 점유 0ms(표본에 잡히지 않음).
- 수정: `UserUseCases.login`·`signup`의 `@Transactional` 제거. 저장(`saveUser`)에만 어댑터 `@Transactional`, `findUser(String)`에 `readOnly` 트랜잭션(없으면 스트림 읽기에서 IllegalStateException이 나는 것을 확인). 응답 계약·중복 방지(유일 제약)·동시 가입 테스트 불변.
- 한계: 풀을 작게 한 동시 부하로 다른 요청의 대기 시간을 직접 측정하지는 않았다(연결 점유 시간으로 대체). 표본 주기보다 짧은 점유는 0ms로 보인다. `RollbackFailureTest.useCasesAreTransactionalProxies`가 users를 CGLIB 프록시/트랜잭션 속성으로 단언하므로 갱신이 필요하다(소유 범위 밖, 아래 보고).
