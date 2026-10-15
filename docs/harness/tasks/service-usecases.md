# service-usecases

- 배경: 사용자가 코드를 보다가 유스케이스에 `@Service`를 쓰지 않는 이유를 물었다. 원래 이유는 application 계층을 Spring에서 분리하기 위한 의도였지만,
  PR #32에서 `@Transactional`을 허용하면서 그 근거가 이미 약해졌다. 조립 코드(`ApplicationConfiguration`의 `@Bean`)가 늘고 관례와 다르다는 비용도 있었다.
- 사용자 결정: "바꿔" — `@Service`로 등록한다.
- 계약:
  1. `UserUseCases`·`TransferUseCases`에 `@Service`(컴포넌트 스캔 + 생성자 주입). `ApplicationConfiguration`의 `users(...)`·`transfers(...)` 수동 `@Bean` 삭제.
  2. `Duration rateMaxAge`는 타입 주입이 모호하므로 `RateFreshnessPolicy` record로 감싸 설정 쪽 빈으로 둔다. 범위 검증(1~3600)과 기동 시 거부 메시지는 그대로.
  3. `ArchitectureTest`: application 계층이 허용하는 Spring 타입은 `Transactional`과 `Service` 두 개뿐이며 그 밖의 `stereotype`(예: `Component`)·transaction 타입·Spring·JPA 참조는 계속 금지. domain은 Spring 전체 금지.
- 불변식: 동작·API·오류 계약 불변. 기존 146개 테스트와 PostgreSQL 17개 테스트가 통과. Spring 없이 유스케이스를 생성자로 만들어 단위 테스트하는 것(`RatePolicyTest`)은 계속 가능하다.
- 완료 기준: 중복 빈 없음, 프록시 적용 유지, 기동 거부 동작 유지, check·bootJar·postgresTest 통과, jar 스모크, 문서(낡은 "application은 Spring에 의존하지 않는다" 서술 포함) 정정.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과와 직접 확인
- 구현: 두 유스케이스에 `@Service`, `users(...)`·`transfers(...)` 수동 `@Bean` 삭제, `RateFreshnessPolicy`(application, record) 신설과 설정 쪽 `@Bean`(검증·메시지 유지), `ArchitectureTest` 허용 타입 2개(`Transactional`, `Service`)와 `Service` 양성 대조.
- 테스트: 기본 check 148개(기존 146 + `Service` 양성 대조 1 + 허용 접두사 충돌 방지 확인 1), postgresTest 17개 통과, 실패·건너뜀 0. `RollbackFailureTest`·`RatesApiTest` 기동 거부 테스트는 수정 없이 통과(프록시 적용·허용 지연 검증 동작 유지).
- 실제 jar 기동(스크래치 디렉터리): 가입 200, 접수 200, 재접수 409, 오류 로그 0건. `REMITTANCE_RATES_MAX_AGE_SECONDS=0`은 기존 메시지(`remittance.rates.max-age-seconds는 1 이상 3600 이하의 정수여야 합니다.`)로 기동 거부.
- 문서 정정: README의 "domain/application은 Spring에 의존하지 않는다"는 PR #32(`@Transactional` 허용) 때 이미 낡은 서술이었고 이번에 실제 허용 범위로 고쳤다.
- 한계: application의 Spring 참조 허용이 두 타입으로 늘었다. 유스케이스를 Spring 없이 쓰려면 생성자 호출로 가능하지만 `@Service`/`@Transactional`이 클래스에 남는다.
- 리뷰 후 반영: `ArchitectureTest`가 허용 타입을 접두사가 아닌 타입 표기 전체(`Lorg/springframework/stereotype/Service;`, `...Transactional;`)로 지우도록 보강(`ServiceLocator` 같은 다른 타입이 가려지는 허점 방지)하고 확인 테스트 추가. 반영분은 재리뷰 없이 `verify`로만 확인.
