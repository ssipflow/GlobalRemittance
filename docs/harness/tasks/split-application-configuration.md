# split-application-configuration

- 배경: 책임 분리 리팩터링 3개 PR 중 ②. `bootstrap/ApplicationConfiguration`(약 160줄)에 JSON 엄격 설정(웹 관심사), Clock, BCrypt 해시 **익명 구현**(어댑터 코드가 bootstrap에 있음),
  AES·JWT 빈, 환율 공급처 선택·검증, 트랜잭션 매니저, 설정값 파서가 섞여 있었다.
- 사용자 결정: "지금 이야기한 거 진행할 것"(① JpaStore 분리 → ② ApplicationConfiguration 정리 → ③ ApiModels 분리·커서 클래스 추출, 필드명 정리 포함).
- 계약(배치는 내가 정함):
  1. 어댑터 구현은 어댑터로: `BCryptPasswordHasher`(`@Component`), `AesIdentityCipher`·`JwtTokens`는 `@Component`로 등록하고 수동 `@Bean`을 제거(키는 생성자의 `@Value`).
  2. JSON 엄격 설정은 웹 어댑터로: `adapter/web/JsonConfiguration`(이미 `OpenApiConfiguration`·`SecurityConfiguration`이 어댑터 로컬 설정인 선례).
  3. bootstrap은 조립만: `ClockConfiguration`, `RatesConfiguration`(환율 공급처·허용 지연), `PersistenceConfiguration`(트랜잭션 매니저), 공용 파서 `ConfigValues`(package-private).
  4. 필드명을 타입 이름에 맞춘다(`passwords`→`passwordHasher`, `tokens`→`tokenProvider`, `rates`→`rateProvider` 등).
- 불변식: 기동 시 설정 검증 실패의 예외 타입·메시지, 빈 의미, 환경변수·프로퍼티 이름 불변. 기존 155개와 PostgreSQL 17개 테스트가 통과한다. 빈 누락·중복 없음.
- 완료 기준: `ApplicationConfiguration` 잔존 참조 없음, 파서 단위 테스트, check·bootJar·postgresTest 통과, jar 스모크(정상·잘못된 환율/키 설정 기동 거부), 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과와 직접 확인
- 이동 대응표: `strictJson` → `adapter/web/JsonConfiguration`, `clock` → `ClockConfiguration`, `passwords` 익명 구현 → `adapter/security/BCryptPasswordHasher`(`@Component`), `cipher`·`tokens` @Bean → `AesIdentityCipher`·`JwtTokens`의 `@Component`+`@Value`,
  `rates`·`rateFreshnessPolicy` → `RatesConfiguration`, `transactionManager` → `PersistenceConfiguration`, `integer`·`bool` → `ConfigValues`. `ApplicationConfiguration`은 삭제(`grep` 잔존 0건).
- 필드·생성자 파라미터명 정리: `UserUseCases`(accountRepository, passwordHasher, identityCipher, tokenProvider), `TransferUseCases`(accountRepository, quoteRepository, rateProvider). 로직·인자 순서 불변.
- 테스트: 신규 `ConfigValuesTest` 21개(경계, 부호·공백·소수·전각 숫자·10자리 거부, "007" 허용, 메시지에 키·범위 포함·입력값 미포함, bool). 기본 check 176개(기존 155 + 21), postgresTest 17개 통과, 실패·건너뜀 0. 기존 테스트는 `SecurityAdaptersTest` 한 줄 외 수정 없이 통과.
- 실제 jar 기동(스크래치 디렉터리): 가입·접수 200, JSON 엄격 설정 유지(문자열 금액 400), 오류 로그 0건. 잘못된 설정 4종은 기존과 같은 예외 메시지로 기동 거부:
  `remittance.rates.max-age-seconds는 1 이상 3600 이하의 정수여야 합니다.`, `remittance.rates.source는 configured 또는 simulated여야 합니다.`, `REMITTANCE_AES_KEY must be Base64.`, `REMITTANCE_JWT_SECRET must be Base64.`
- 동작 차이 가능성: 빈 이름이 `passwords`·`cipher`·`tokens`에서 `bCryptPasswordHasher`·`aesIdentityCipher`·`jwtTokens`로 바뀐다(이름으로 조회하는 코드는 없고 모두 타입 주입). 키 검증 실패 예외가 `@Bean` 메서드 대신 컴포넌트 생성 실패로 감싸지는 경로가 달라졌지만 근원 예외의 타입·메시지는 같다.
- 한계: 환율 설정 검증은 여전히 DB 초기화 뒤에 일어난다(이번에 바꾸지 않음). 설정 클래스의 위치 기준(어댑터 로컬 설정 vs bootstrap)은 선례에 맞춰 내가 정했다.
- 리뷰 후 반영: `architecture.md`의 adapter/web·security 행에 새 클래스 추가, 이전 PR(#32)에서 남은 낡은 문장("Work의 트랜잭션")을 `@Transactional`로 정정. 반영분은 재리뷰 없이 `verify`로만 확인. 리뷰 지적 중 `999999999`(9자리 최대) 허용 경계 케이스는 추가하지 않았다(정규식 길이 제한과 범위 상한 케이스는 있음).
