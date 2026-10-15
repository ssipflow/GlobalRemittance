# transactional-usecases

- 배경: 설계 대화의 ② 단계. 유스케이스 본문이 `work.required(() -> {...})` 람다로 감싸여 읽기 비용이 컸고, Spring 의존을 막으려고 만든 `Work` 포트와
  `TransactionalWork`(TransactionTemplate)가 관례와 달랐다. 사용자가 "Transactional로 쓰고 싶다"고 결정했다.
- 사용자 결정: application 계층에서 Spring 트랜잭션 어노테이션을 허용한다(어노테이션은 메타데이터라는 실용 입장). 다른 Spring 의존은 계속 금지.
- 계약:
  1. `TransferUseCases`·`UserUseCases`의 기존 `work.required` 메서드마다 `@Transactional`(속성 없는 기본값). 이력 조회도 `readOnly`로 바꾸지 않는다(동작 불변; readOnly는 후속 개선 후보).
  2. `Work` 포트와 `TransactionalWork`를 제거한다. CGLIB 프록시를 위해 유스케이스 클래스의 `final`을 푼다.
  3. `ArchitectureTest`: application은 `org/springframework/transaction/annotation/`만 예외로 허용, domain은 변함없이 Spring 전체 금지.
  4. `TransactionalWork`가 하던 "롤백 자체가 실패해도 원래 BusinessException을 전달" 처리가 `@Transactional`에서도 필요한지는 가정하지 않고 결정적 재현 테스트로 확인한다.
- 불변식: 오류 코드·롤백 의미·락 순서·응답 불변. 기존 128개와 PostgreSQL 17개 테스트가 통과한다.
- 완료 기준: 프록시/트랜잭션 적용을 고정하는 테스트, 롤백 실패 시 원래 예외 전달 회귀 테스트, check·bootJar·postgresTest 통과, 실제 jar 기동 스모크, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과와 직접 확인
- 구현: `TransferUseCases`·`UserUseCases`의 업무 메서드 5개에 `@Transactional`(기본값), `final` 제거, `Work` 포트·`TransactionalWork` 삭제, `ArchitectureTest`는 application에서 `org/springframework/transaction/annotation/`만 예외 허용(양성 대조와 다른 Spring 패키지는 계속 금지됨을 보이는 확인 포함).
- **실험 결과(가정하지 않고 확인)**: `@Transactional`만 전환하자 H2 `lockWaitTimeoutIsBusinessErrorAndRecoversAfterRelease`가 `expected 503 but was 500`으로 깨졌다(implementer 보고. 이 실패 화면은 제가 직접 보지 못했고, 결정적 재현 테스트가 같은 현상을 고정한다). 결정적 재현(롤백이 실패하는 `JpaTransactionManager`)에서도
  호출자가 원래 `BusinessException`이 아니라 `JpaSystemException`을 받았다. 따라서 보존 장치가 필요했고 `RollbackSafeTransactionManager`(JpaTransactionManager 상속, `doRollback` 실패를 삼키고 클래스명·SQLState만 WARN)를 추가했다.
  Boot의 `JpaTransactionManager` 자동 구성은 `@ConditionalOnMissingBean`이라 이 빈이 있으면 물러난다(컨텍스트 테스트가 주입된 매니저의 타입을 고정).
- 의미 차이 ①(리뷰 지적): `signup`·`login`의 입력 형식 검증이 트랜잭션 안으로 들어왔다. 오류 코드·응답은 같지만 잘못된 입력도 빈 트랜잭션을 열고 닫으며 풀 연결을 잠깐 잡을 수 있다. 소유 파일 범위 때문에 이번에는 받아들이고 문서에 적었다(후속에서 검증을 트랜잭션 앞으로 빼는 방법을 검토할 수 있다).
- 의미 차이 ②: 이전 `TransactionalWork`는 `BusinessException`만 보존하고 그 외에는 롤백 예외를 던졌다. 새 방식은 모든 원래 예외를 보존한다(더 일관되지만 동작이 조금 넓어졌다).
- 새 테스트: `RollbackFailureTest` 4개(기본 매니저가 덮음을 재현, 안전 매니저가 원래 예외 보존과 EntityManager 정리, 앱이 안전 매니저를 쓰는지, 유스케이스가 CGLIB 프록시이며 5개 메서드 모두 readOnly 아닌 트랜잭션 속성),
  `ArchitectureTest` 2개. 기본 check 134개(기존 128 + 6), postgresTest 17개 통과, 실패·건너뜀 0.
- 실제 jar 기동 스모크(스크래치 디렉터리): 가입 200·중복 가입 409, 로그인, 견적 3개 발급·접수 200, 같은 견적 재접수 409 `ALREADY_REQUESTED`, 없는 견적 404, 이력 건수 3, 로그에 ERROR 0건.
  (스모크 도중 한 번 400이 나온 것은 zsh 배열 첨자(1부터) 때문에 빈 `quoteId`를 보낸 제 스크립트 오류였고 정확한 순서로 재현해 회귀가 아님을 확인했다.)
- 한계/우려: 롤백 실패를 삼키므로 커넥션 상태가 불확실한 채 풀에 반환될 수 있다(Hikari가 반환 시 정리하는지는 확인하지 않음). 롤백 실패 보존은 PostgreSQL에서는 검증하지 않았다(H2 락 타임아웃과 Mockito 재현).
  history를 readOnly로 바꾸는 개선은 동작 변경이라 하지 않았다.

- 리뷰 후 추가 반영: `TransactionManagerCustomizers` 연결(직접 등록한 매니저가 Boot 기본 구성을 대체하므로) + 적용 테스트, `ArchitectureTest` 허용 범위를 `Transactional` 한 타입으로 축소. 반영분은 재리뷰 없이 `verify`로만 확인.
