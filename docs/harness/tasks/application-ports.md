# application-ports

- 배경: 사용자 의견 — `application/Ports`가 중첩 인터페이스 9개를 담는 이름 공간 클래스라 파일이 크고 주입할 때마다 `Ports.Accounts` 식으로 써서 가독성이 떨어진다.
  설계 대화에서 합의한 리팩터링 순서의 1단계다(① Ports 분리 → ② `@Transactional` 전환 → ③ 한도·멱등성 재현창의 도메인 이동).
- 사용자 결정: "Ports를 application/port 패키지로 분리해서 하위 인터페이스들 전부 독립적으로 구성". 3번(Money/Remittance 모델링)과 5번(낙관적 락)은 하지 않기로 했다.
- 계약:
  1. `com.nelson.remittance.application.port` 패키지에 인터페이스를 한 파일씩 둔다: `AccountRepository`, `QuoteRepository`, `RateProvider`, `PasswordHasher`, `IdentityCipher`, `TokenProvider`, `Work`.
     `QuoteRepository`의 중첩 record는 최상위 `QuotePosition`, `DailyTotal`로 승격한다. `Work`는 ② 단계에서 제거 예정이라 이름·시그니처를 유지한다.
  2. 이름은 내가 정했다(역할이 드러나는 이름). 메서드 시그니처·의미·주석은 그대로 옮긴다. `JpaStore`가 두 저장소를 함께 구현하는 구조도 유지한다(분할은 범위 밖).
  3. `Quotes`를 명령·조회로 더 쪼개는 일(예: 이력 조회 전용 인터페이스)은 하지 않았다.
- 불변식: 동작·로직·API·DB 변경 없음. 기본 테스트 128개와 postgresTest 17개가 수정 없이(타입 이름 변경 외) 통과해야 한다.
- 완료 기준: `grep Ports src` 0건, check·bootJar·postgresTest 통과, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.
