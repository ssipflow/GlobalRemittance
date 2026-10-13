# flyway-migrations

- 배경: 스키마를 `ddl-auto: update`에 맡기고 있어 변경 이력이 코드로 남지 않는다. 멱등성 키 도입(PR #16) 때 기존 파일 DB에서 컬럼과 유일 제약이 추가되는지
  실제 DB 파일로 확인하지 못했고 문서에 "미검증"으로 남겼다.
- 사용자 결정: 후속 후보 중 Flyway 도입을 권장안으로 진행.
- 계약:
  1. 스키마는 `db/migration`의 Flyway SQL이 만든다. V1 = 멱등성 도입 이전 스키마(실제 d2c8703 직전 앱이 만든 DDL을 H2 `SCRIPT NODATA`로 확인해 옮김),
     V2 = `idempotency_key` 컬럼과 (user_id, idempotency_key) 유일 제약.
  2. 이력 없는 기존 파일 DB는 `baseline-on-migrate` + `baseline-version: 0`으로 기준선을 잡고 V1부터 다시 실행한다. 그래서 V1·V2는 `IF NOT EXISTS`로 쓴다.
  3. 운영·테스트 모두 `ddl-auto: validate`. 엔티티와 SQL이 어긋나면 기동이 실패한다.
- 불변식: 기존 데이터(사용자·견적·접수)가 업그레이드 뒤에도 그대로이고, 유일 제약이 중복 생성되지 않으며, 두 번째 기동은 마이그레이션이 없다. API·오류 계약은 바뀌지 않는다.
- 완료 기준: 빈 DB, 구 DB(V1 상태), 이력만 없는 최신 DB, 이력 없는 구 DB의 자동 검증(MigrationTest), 기존 테스트 전부 통과(validate 포함), 실제 구 DB 파일과
  현 dev가 만든 DB 파일로 기동 확인, Java 17 check·bootJar, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과와 직접 확인
- 구현: V1·V2 SQL, flyway-core, `ddl-auto: validate`, `baseline-on-migrate`·`baseline-version: 0`. 테스트 설정은 `create-drop`을 제거해 Flyway로 스키마를 만들고 validate한다.
  MigrationTest 4개(빈 DB, V1→최신 업그레이드와 유일 제약, 이력만 없는 최신 DB, 이력 없는 구 DB).
- 직접 확인(수동, 스크래치 DB): 멱등성 도입 직전 커밋의 앱으로 파일 DB(사용자 1, 접수 1)를 만든 뒤 새 앱으로 열었다.
  버전 0 기준선 → V1·V2 적용, 기존 사용자 로그인·이력 조회 정상, `Idempotency-Key` 접수 200과 재현 200, 두 번째 기동은 "up to date".
  현 dev(`ddl-auto: update`)가 만든 파일 DB도 열렸고 유일 제약은 하나만 존재했다.
- 검증: 기존 67개 테스트가 Flyway + validate로 통과(엔티티·SQL 일치 확인). 최종 `clean check bootJar`: 71개 통과, 실패·건너뜀 0.
- 독립 리뷰: 차단 결함·사실 오류 없음. 제안 중 README 문구 정정과 design.md 한계 한 줄을 반영했다(검증은 verify로 확인, 재리뷰 없음).
- 한계: Flyway 10.10이 번들 H2 2.2.224보다 오래돼 기동 시 경고가 나온다(동작은 정상). validate는 유일 제약·인덱스·FK 이름을 확인하지 않는다.
  MigrationTest의 "이력만 없는 최신 DB"는 Hibernate가 만든 실제 DDL이 아니라 Flyway 스키마에서 이력을 지워 재현했다. 운영 DB 전환 시 H2 전용 문법(ENUM 등)은 바꿔야 한다.
