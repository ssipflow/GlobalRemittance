# package-bootstrap

- 배경: 패키지 재구성 5개 PR 중 4번째. `bootstrap`에 설정 클래스와 시작 실패 진단이 한 패키지에 섞여 있었다.
- 계약:
  1. `bootstrap.config`(ClockConfiguration, RatesConfiguration, PersistenceConfiguration, ConfigValues), `bootstrap.diagnostics`(DatabaseConnectionFailureAnalyzer, LockTimeoutEnvironmentPostProcessor, LockTimeoutFailureAnalyzer, InvalidLockTimeoutException).
  2. `META-INF/spring.factories`의 FQCN 3개를 `bootstrap.diagnostics.*`로 갱신한다. 컴파일러가 못 잡는 문자열이라 시작 실패 진단이 실제로 동작하는지 jar로 확인한다.
  3. `ConfigValues`는 `RatesConfiguration`만 쓰므로 같은 `config`에 두어 package-private을 유지한다. `ConfigValuesTest`도 같은 패키지로 이동한다. `ObservabilityTest`의 FQN 문자열도 고친다.
- 불변식: 로직·설정·API 불변.
- 완료 기준: 옛 경로 잔존 없음, check·bootJar·postgresTest 통과, OpenAPI 비교, 잘못된 잠금 대기 설정과 DB 연결 실패에서 진단 메시지 확인(jar), 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람. `finish`는 `diff.renames=false` 환경변수로 실행한다. publish `--repo`는 ssipflow/GlobalRemittance.

## 구현 결과와 직접 확인
- 구현은 implementer가 이동·import·spring.factories 수정을 했고, 직접 검증했다.
- 테스트: check 194개, postgresTest 17개 통과, 실패·건너뜀 0(결과 XML 합산).
- OpenAPI 명세: 변경 전 jar와 `servers`를 제외하고 동일.
- **문자열 FQCN(spring.factories) 동작 확인(jar, 스크래치 디렉터리)**: `remittance.lock-timeout-ms=abc`로 기동하면 "APPLICATION FAILED TO START"와 `REMITTANCE_LOCK_TIMEOUT_MS … 1~30000 사이의 정수` 진단이 나오고, 접속 불가 PostgreSQL URL로 기동하면 "데이터베이스 서버에 연결하지 못해 Flyway가 접속하지 못했습니다. (SQLState 08001)" 진단이 나온다. 즉 이동한 분석기·EnvironmentPostProcessor가 모두 등록돼 동작한다.
- jar 스모크: 가입·견적·접수·이력 200, 앱 ERROR 로그 0건.
- 한계: 테스트 패키지 정리는 다음 PR이다.
