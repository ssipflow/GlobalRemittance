# split-jpa-repositories

- 배경: 사용자가 코드를 보다가 "기본 틀은 잡혔는데 클래스와 인터페이스의 책임이 명확히 구분되지 않는다"고 지적했다. 구체적으로 `JpaStore`가 계정·견적 두 저장소를 한 클래스에서
  구현했다(230줄, ID 범위 변환·락 타임아웃 판별·유일 제약 위반 변환 포함). 이후 합의한 3개 PR의 첫 번째다
  (① `JpaStore` 분리 → ② `ApplicationConfiguration` 정리 → ③ `ApiModels` 분리·커서 클래스 추출). 유스케이스 분리와 `adapter/in·out` 패키지 구조 변경은 제외하기로 했다.
- 사용자 결정: "지금 이야기한 거 진행할 것". 새 이름은 내가 정했다(`JpaAccountRepository`, `JpaQuoteRepository`).
- 계약:
  1. `JpaAccountRepository`(계정 저장, 사용자 행 잠금)와 `JpaQuoteRepository`(견적 저장·조회·집계)로 분리한다. 메서드 본문·JPQL·주석·예외 코드는 그대로 옮긴다.
  2. 두 클래스에 중복되던 오류 판별(락 타임아웃, 유일 제약 위반)은 `PersistenceErrors`로, ID 범위 변환은 `EntityIds`로 추출한다(둘 다 package-private).
  3. `QuoteRepository`를 명령/조회로 더 쪼개는 일(이력 조회 전용 포트)은 이번 범위가 아니다.
- 불변식: 동작·쿼리·오류 코드·락 순서 불변. 기존 148개와 PostgreSQL 17개 테스트가 통과한다. `AccountRepository`/`QuoteRepository`가 각각 단일 빈으로 해석된다.
- 완료 기준: `JpaStore` 잔존 참조 없음, 추출한 유틸의 단위 테스트, check·bootJar·postgresTest 통과, jar 스모크, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과와 직접 확인
- 구현: `JpaAccountRepository`(findUser×2, lockUser, saveUser), `JpaQuoteRepository`(findQuote, findByIdempotencyKey, saveQuote, requestedPage, requestedDailyTotal, requestedUsdTotal), `PersistenceErrors`(락 타임아웃·유일 제약 위반 판별), `EntityIds`(ID 범위 변환). `JpaStore`는 삭제.
- 쿼리 동일성: 제가 `git show HEAD:...JpaStore.java`의 JPQL 문자열 10줄과 새 두 저장소의 JPQL 문자열을 정렬해 비교한 결과 완전히 같다.
- 테스트: 신규 `PersistenceErrorsTest` 7개(락 타임아웃 판별, 원인 연쇄 위치, 무관 예외, 순환 종료, 유일 제약 위반 판별, ID 변환 경계). 기본 check 155개(기존 148 + 7), postgresTest 17개 통과, 실패·건너뜀 0. 기존 테스트는 수정 없이(주석 한 줄 제외) 통과.
- 실제 jar 기동(스크래치 디렉터리): 가입 200·중복 가입 409(유일 제약 변환), 견적 3개 접수, 같은 키 재요청 200과 키 재사용 422, 이력 페이지, 오류 로그 0건.
- 동작 차이 가능성: 원인 연쇄에서 `getCause()==self` 순환이 있으면 기존 코드는 무한 루프였고 이제는 순환을 끊고 종료한다(실제 DB 예외에서 생긴다는 근거는 없으며 생기지 않는 것으로 본다). 그 외 동일.
- 한계: `QuoteRepository`는 명령과 조회가 한 인터페이스에 남아 있다. 두 저장소 모두 같은 `EntityManager` 프록시를 쓰고 `@Repository` 예외 번역 대상이다.
