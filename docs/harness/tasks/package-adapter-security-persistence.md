# package-adapter-security-persistence

- 배경: 패키지 재구성 5개 PR 중 3번째. `adapter/persistence`에 엔티티·저장소·트랜잭션 매니저가, `adapter/security`에 암호화·JWT·설정이 한 패키지에 섞여 있었다.
- 계약:
  1. `adapter.persistence.entity`(UserEntity, QuoteEntity), `repository`(JpaAccountRepository, JpaQuoteRepository, PersistenceErrors, EntityIds), `tx`(RollbackSafeTransactionManager).
  2. `adapter.security.crypto`(BCryptPasswordHasher, AesIdentityCipher), `jwt`(JwtTokens, JwtAuthenticationFilter), `SecurityConfiguration`은 루트.
  3. 엔티티 필드는 package-private으로 유지한다. 다른 패키지에서 필요한 생성자·`toDomain()`만 public으로 하고, 저장소가 필드를 직접 대입하던 접수 갱신은 `QuoteEntity.markRequested(requestedTime, idempotencyKey, updatedAt)`로 대체한다.
  4. `PersistenceErrors`·`EntityIds`는 저장소 패키지 안에서만 쓰므로 package-private 유지(`PersistenceErrorsTest`도 같은 패키지로 이동).
- 불변식: 로직·JPQL·API·OpenAPI 명세 불변. JPQL은 엔티티·필드 이름 기반이라 접근 제어자·패키지 이동의 영향이 없다.
- 완료 기준: 옛 경로 잔존 없음, check·bootJar·postgresTest 통과, OpenAPI 비교, jar 스모크, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람. `finish`는 `GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=diff.renames GIT_CONFIG_VALUE_0=false` 환경변수로 실행한다. publish `--repo`는 ssipflow/GlobalRemittance.

## 구현 결과와 직접 확인
- 구현은 implementer가 이동·import 수정을 했고, Spotless import 정렬 위반 1건(`SecurityConfiguration`)은 `spotlessApply`로 고쳤다.
- 테스트: 기본 check 194개, postgresTest 17개 통과, 실패·건너뜀 0(결과 XML 합산).
- OpenAPI 명세: 변경 전(dev) jar와 변경 후 jar의 `/v3/api-docs`가 `servers`를 빼면 동일.
- jar 스모크(스크래치 디렉터리): 가입·로그인·견적·접수·같은 키 재접수(replay)·이력 모두 200, 인증 없는 이력 401, 404 응답에 요청 ID 헤더, 앱 ERROR 로그 0건.
- 한계: bootstrap(spring.factories)·테스트 패키지는 다음 PR이다.
