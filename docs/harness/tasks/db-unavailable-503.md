# db-unavailable-503

- 배경: 백로그 후보 2 중 사용자가 "연결 끊김 직후 500 완화"를 선택하고 방식은 "503 + Retry-After 매핑"으로 결정. 결정 기록: [DB 연결 장애 503](../../decisions/db-unavailable-503.md).
- 계약:
  1. DB 연결 장애(SQLState 08xxx·57P01~03, `SQLTransient/NonTransientConnectionException`)는 `Retry-After: 1`의 503 `DB_UNAVAILABLE`, 로그는 WARN. 잠금 대기·제약 위반 등 다른 오류는 기존 그대로(500 또는 기존 매핑).
  2. 연결이 끊긴 직후 첫 요청이 500이 아니고, 같은 Idempotency-Key 재시도가 성공하며 접수는 정확히 1건이다.
  3. 도메인·application 계층 불변. 기본 `check`는 Docker 없이 통과.
- 완료 기준: 단위 테스트(판별 경계)·postgres 통합 테스트 통과, check·bootJar·postgresTest, 문서(api/README/design/architecture) 갱신, dev PR.
- 종료: 커밋·push·dev PR 후 옵시디언 sync(앱 문서 변경). 승인·병합은 사람.

## 구현 결과와 직접 확인
- 변경: `DatabaseUnavailability`(신규), `ApiExceptionHandler`, `TransferController`의 OpenAPI 503 설명, 테스트 2개 추가.
- 수정 전 재현: `PostgresStaleConnectionTest`가 500으로 실패하고 로그의 원인은 `JpaSystemException <- GenericJDBCException <- PSQLException(sqlState=57P01)`(`lockUser`에서 발생).
- 수정 후: 같은 테스트 통과(첫 실패 503 + `Retry-After: 1`, 재시도 200, 접수 1건).
- 독립 리뷰: 차단 결함 없음. 반영: 낡은 서술 3곳(design 2·overview 1), 커밋 중 끊김 불확실성 단서(api·README·핸들러 주석), `08P01` 제외와 테스트, 종료 대기 폴링 실패 시 명시적 AssertionError.
  권고(미반영): 첫 실패 단언이 Hikari 500ms 창에 의존해 매우 느린 환경에서 오탐 가능(로컬 반복 실행 통과), 과거 기록 "첫 요청 500"은 당시 관찰이라 유지.
  후속(소유 파일 밖): overview.md의 "LOCK_TIMEOUT의 Retry-After: 1" 문장에 DB_UNAVAILABLE 병기.
