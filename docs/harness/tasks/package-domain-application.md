# package-domain-application

- 배경: 책임 분리 리팩터링(① #35 JpaStore, ② #36 ApplicationConfiguration, ③ #37 ApiModels)은 지적된 클래스만 대상이었고, 패키지 구조 변경은 내가 "효과 대비 이동량이 크다"며 제외하자고 권했다.
  사용자가 "왜 다른 패키지 이하 클래스들은 적용하지 않았느냐, 전부 책임에 따라 패키지 리팩토링을 진행하라"고 지시했다. 기술적 이유가 아니라 범위 판단이었다.
- 사용자 결정: 전체 패키지 재구성(PR 5개로 분할). 이번이 1번째(domain + application). 사용자 요구: domain은 하위 클래스 책임이 섞여 있으므로 분리하고, 특히 Exception은 운영하며 늘어날 수 있으니 별도 패키지로 둔다.
  port는 Repository를 구분한다. web은 Configuration·Model·Controller를 같은 계층에 두지 않는다(다음 PR). forex·security는 크게 나누지 않는다.
- 계약:
  1. domain: `exception`(BusinessException, ErrorCode), `quote`(Quote, QuotePolicy, RateSnapshot), `user`(User), `time`(BusinessDay).
     `BusinessException`의 중첩 enum `Code`(14개)를 최상위 `ErrorCode`로 분리(이름·순서·값 불변, `QUOTE_EXPRIED` 철자 포함). 앞으로 코드가 늘어날 곳이며 `Ports`·`ApiModels`와 같은 이름 공간 패턴 해소.
  2. application: `user`(UserUseCases), `transfer`(TransferUseCases, RateFreshnessPolicy), `port.repository`(AccountRepository, QuoteRepository, QuotePosition, DailyTotal), `port.security`(PasswordHasher, IdentityCipher, TokenProvider), `port.rate`(RateProvider).
  3. 패키지 이동으로 깨지는 package-private 접근은 가장 좁게 해결하고 보고한다.
- 불변식: 로직·시그니처·오류 코드 값·API·OpenAPI 명세 불변. 기존 194개와 PostgreSQL 17개 테스트가 통과한다.
- 완료 기준: 옛 경로·`BusinessException.Code` 잔존 없음, check·bootJar·postgresTest 통과, OpenAPI 명세 비교, jar 스모크, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람. `finish`는 rename 감지 때문에 `GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=diff.renames GIT_CONFIG_VALUE_0=false` 환경변수로 실행한다.

## 구현 결과와 직접 확인
- 구조: domain(`exception`, `quote`, `time`, `user`), application(`user`, `transfer`, `port.repository`, `port.security`, `port.rate`). 옛 경로 파일·빈 디렉터리 없음(제가 `find`로 확인), 옛 FQN 참조 0건(`grep`).
- `ErrorCode`: 14개 값을 순서·이름 그대로 분리(`QUOTE_EXPRIED` 철자 포함), 이름을 참조하는 파일       25 개(`ErrorCode.java` 자신 포함, `grep -rlw ErrorCode src` 기준). `BusinessException.Code`·`domain.BusinessException` 잔존 0건.
- 접근 제어를 넓힌 곳 없음(main이 그대로 컴파일, 테스트도 패키지 이동 없이 통과). `RollbackFailureTest``RollbackFailureTest`의 클래스 리터럴 2곳(`...port.repository.QuotePosition.class`, `...domain.user.User.IdentityType.class`)만 새 패키지로 수정.
- 테스트: 기본 check 194개(변동 없음), postgresTest 17개 통과, 실패·건너뜀 0. `ArchitectureTest`는 수정하지 않았다(`Files.walk` 재귀 검사라 새 하위 패키지도 대상).
- **OpenAPI 명세 비교(제가 직접)**: 변경 전(`dev`, #37) 명세와 변경 후 jar의 `/v3/api-docs` JSON을 비교해 `servers` 포트를 빼고 완전히 같다.
- 실제 jar 기동(스크래치 디렉터리): 견적 4개 중 3건 접수 200, 4번째 400 `LIMIT_EXCESS`, 같은 견적 재접수 409 `ALREADY_REQUESTED`, 오류 로그 0건.
- 한계: 이번 PR은 domain·application만 다룬다. adapter/web·security·persistence·bootstrap·테스트 패키지는 다음 PR에서 이어간다.
