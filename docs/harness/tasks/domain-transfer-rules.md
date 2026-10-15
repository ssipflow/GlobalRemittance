# domain-transfer-rules

- 배경: 설계 대화에서 합의한 리팩터링 3단계 중 ③. 이 프로젝트의 가장 중요한 규칙(일일 한도 비교, 멱등성 재현 24시간 창, 견적 유효시간 600초, KST 기준일)이
  `TransferUseCases`에 있어 도메인이 빈약(anemic)하고 유스케이스가 트랜잭션 스크립트에 가까웠다.
- 사용자 결정: "한도 검사와 멱등성 재현창은 도메인으로 이동해야지". 3번(Money/Remittance 모델링)과 5번(낙관적 락)은 하지 않는다.
- 계약:
  1. `BusinessDay(start, end)`: now가 속한 KST 날짜의 [00:00, 익일 00:00). KST 시간대 상수의 단일 출처.
  2. `Quote`: `VALIDITY`(10분), `IDEMPOTENT_REPLAY_WINDOW`(24시간), `issue(...)`, `isOwnedBy(...)`, `replays(previous, now)`(다른 견적이면 IDEMPOTENCY_KEY_REUSED, 같은 견적이면 창 안에서만 재현).
  3. `User.checkDailyLimit(requestedToday, newAmount)`: 합계가 한도를 초과하면 LIMIT_EXCESS, 같으면 허용.
  4. `TransferUseCases`는 락(첫 DB 작업이 `lockUser`)·저장소 조회·트랜잭션 조정만 하고 규칙은 도메인에 위임한다. 검사 순서는 그대로다:
     (키 재현 판단) → 소유권 → 중복 → 만료 → (일일 합계 조회) → 한도 → 저장.
- 불변식: 오류 코드·검사 순서·락 순서·경계(만료 시각과 같은 순간은 만료, 한도와 같은 금액은 허용, 24시간 경계)·응답 불변. 기존 테스트가 수정 없이 통과한다.
- 완료 기준: 도메인 경계 단위 테스트, check·bootJar·postgresTest 통과(안전망), 실제 jar 스모크, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과와 직접 확인
- 구현: `BusinessDay`(신규), `Quote.issue/isOwnedBy/replays`와 상수 `VALIDITY`·`IDEMPOTENT_REPLAY_WINDOW`, `User.checkDailyLimit`, `TransferUseCases`는 조정만, `TransferUseCases.KST` 제거(`JwtTokens`·`ApiModels`는 `BusinessDay.KST`).
- 동등성: 독립 리뷰어가 HEAD의 `TransferUseCases`와 줄 단위 대조해 검사 순서·조건·경계·락 순서·견적 만료(600초 == 10분)·KST 구간이 동일함을 확인. 숨은 변경 없음. 제가 직접 읽은 diff와도 일치.
- 테스트: `DomainRulesTest` 11개(Spring 없음). 기본 check 146개(기존 135 + 11), `RemittanceApiTest` 등 기존 테스트 무수정 통과, postgresTest 17개 통과, 실패·건너뜀 0.
- 리뷰 반영: BusinessDay 주석 정정("KST는 항상 24시간" → 현재는 서머타임 없음, 과거는 보장하지 않음), 검사 순서 테스트가 소유권 > 중복 > 만료 전체를 고정하도록 보강, `replays`가 재사용 키에서 예외를 던진다는 주석. 반영분은 재리뷰 없이 `verify`로만 확인.
- 실제 jar 기동(스크래치 디렉터리): 접수 3건(합계 918.93 USD) 후 4번째 `LIMIT_EXCESS`(400), 같은 키·같은 견적 재요청 200으로 건수 불변, 같은 키·다른 견적 422 `IDEMPOTENCY_KEY_REUSED`, 로그 ERROR 0건.
- 한계: 동작 불변 리팩터링이며 도메인을 풍부하게 모델링한 것은 아니다(Money 값 객체 없음, 견적/접수 단일 모델, 여러 견적에 걸친 불변식을 소유하는 애그리게이트가 코드로 드러나지 않음 — 합의에 따라 이번 범위 밖).
  `Quote.replays`는 boolean처럼 보이지만 예외도 던진다(주석으로 명시). 기본 프로파일(H2/PostgreSQL) 방향은 사용자 결정 대기.
