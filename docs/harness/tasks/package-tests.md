# package-tests

- 배경: 패키지 재구성 5개 PR 중 마지막. 테스트 18개가 루트 패키지에 평면으로 놓여 있어 어떤 계층의 테스트인지 알기 어려웠다.
- 계약:
  1. 단위 테스트는 대상의 main 패키지를 미러링: `domain/quote`(QuotePolicyTest), `domain`(DomainRulesTest), `application/transfer`(RatePolicyTest), `adapter/security`(SecurityAdaptersTest), `adapter/persistence/tx`(RollbackFailureTest), `bootstrap/diagnostics`(DatabaseConnectionFailureTest, LockTimeoutSettingsTest).
  2. `integration`(RemittanceApiTest, RatesApiTest, OpenApiTest, ObservabilityTest, H2ConsoleTest, LocalLoggingTest, MigrationTest), `postgres`(Postgres* 3개, 기존 `postgres` 태그 유지), `architecture`(ArchitectureTest).
  3. 문자열 참조 `ObservabilityTest`의 `"…ObservabilityTest.errorDescriptionsExcludeMessages"`를 새 경로로 고친다.
- 불변식: 테스트 로직·개수 불변(check 194, postgresTest 17). 운영 코드 변경 없음. `@SpringBootTest`는 상위 패키지의 `RemittanceApplication`을 찾는다.
- 완료 기준: 루트 직속 테스트 0개, check·bootJar·postgresTest 통과(개수 동일), 문서 갱신.
- 보류: 엔티티 생성자를 다른 계층이 호출하지 못하게 하는 아키텍처 규칙은 코드 변경이라 사용자 확인 후 별도 작업으로 한다.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람. `finish`는 `diff.renames=false` 환경변수로 실행한다.

## 구현 결과와 직접 확인
- 이동은 implementer가 했고, 이동 후 루트 패키지의 `RemittanceApplication`을 import 없이 쓰던 테스트 3곳(`DatabaseConnectionFailureTest`, `RatesApiTest`, `PostgresConnectionFailureTest`)이 컴파일 오류가 나 직접 import를 추가했다. `@SpringBootTest`는 영향이 없다(상위 패키지 탐색).
- 문자열 참조는 `ObservabilityTest` 1곳만 수정. 루트 직속 테스트는 0개.
- 테스트: check 194개(변동 없음), postgresTest 17개 통과, 실패·건너뜀 0(결과 XML 합산). 운영 코드는 변경하지 않았다.
- 한계: 엔티티 생성자 접근 제한 규칙은 이번에 넣지 않았다.
