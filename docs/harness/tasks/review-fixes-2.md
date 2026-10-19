# review-fixes-2

- 배경: 이전 읽기 기반 코드 리뷰 후속 + 사용자 결정 두 가지(최소 금액 5000 KRW, 이메일 대소문자 중복 가입 차단). 결정 근거는
  [min-amount-and-email-normalization](../../decisions/min-amount-and-email-normalization.md).
- 방식: 항목마다 기존 테스트 실패/신규 단언으로 현상을 먼저 고정한 뒤 수정했다.

## 항목별 결과
1. 최소 금액: `QuotePolicy`에 5000 KRW 하한, 새 `AMOUNT_TOO_SMALL`(400). 5000 허용·4999 거절·4999.5(정수화 5000) 허용·4999.4 거절,
   1003원 시나리오는 거절됨을 단언. 5000원의 수취액이 USD 환율 1300에서 3.07, JPY 환율 9에서 194로 양수임을 단위 테스트로 확인.
   k6 금액(1만~3만)은 영향 없음(코드 확인, k6 실행은 하지 않음).
2. 이메일: `UserUseCases`가 가입·로그인에서 소문자 정규화. V3 마이그레이션(H2/PostgreSQL)과 MigrationTest(소문자화, 중복 시 실패·데이터 불변),
   PostgresProfileTest 기대 버전 3, H2 통합 테스트(`emailCaseCannotBypassDuplicateSignup`), 단위 테스트, PostgreSQL 동시 대소문자 가입 테스트.
3. 로그인 타이밍: 없는 사용자도 기동 시 임의 값으로 만든 더미 해시와 `matches`. 시간 측정 대신 matches 호출 횟수·인자를 단위 테스트로 확인.
   `UserUseCases` 생성자에서 BCrypt 한 번이 추가로 실행된다(기동 비용 수백 ms).
4. 보안 기본 거부: 공개 경로 목록 단일 출처(SecurityConfiguration)를 JwtAuthenticationFilter와 공유, 나머지는 인증 필요. 컨트롤러가 없는 경로는
   익명도 통과해 404 JSON 계약 유지(`RequestMappingHandlerMapping` 조회). 결정: 401로 바꾸면 api.md 계약과 기존 테스트가 깨지므로 404를 유지.
   부작용: JWT 필터가 /transfer 밖의 비공개 경로에도 적용되어 그 경로의 잘못된 Authorization 헤더는 401이다. 테스트 전용 컨트롤러
   (`/_test/*`, `/_obs/*`)는 이제 인증이 필요해 테스트가 토큰/`user()`를 쓰도록 바꿨다.
5. `TransferUseCases.history`를 `@Transactional(readOnly = true)`로. 세 쿼리의 일관 스냅샷은 READ COMMITTED에서 보장되지 않으며 주석·design.md에 그대로 적었다.
   RollbackFailureTest는 history만 readOnly임을 단언하도록 바꿨다.
6. `requestedUsdTotal` 포트·구현 삭제, 접수는 `requestedDailyTotal(...).usdAmount()` 사용. 동작 불변.
7. Bearer 스킴 대소문자 무시(`regionMatches`). `bearer`/`BEARER`/`BeArEr` 성공, `Basic`/`Bearer`/`Bearer `/`Bearertoken`/스킴 없음은 401.

## 한계
- 시간 동일성은 측정으로 증명하지 않았다. 이메일 소문자화는 DB `LOWER`와 Java 변환 차이(비 ASCII)가 남는다.
- H2 V3 중복 실패 메시지는 제약명 수준이다. 실제 운영 DB 데이터로 V3을 실행해 보지는 않았다.
- 기본 거부의 매핑 조회는 컨트롤러 매핑만 본다. 정적 리소스 핸들러는 보지 않으며 이 앱은 정적 리소스를 제공하지 않는다.
