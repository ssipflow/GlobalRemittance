# split-api-models

- 배경: 책임 분리 리팩터링 3개 PR 중 ③(마지막). `adapter/web/ApiModels`(153줄)는 요청/응답 record 10개와 시간 포맷 헬퍼를 담는 `final` 이름 공간 클래스로, 예전 `Ports`와 같은 패턴이었다.
  `TransferController`(241줄)에는 라우팅과 OpenAPI 어노테이션에 더해 이력 페이징(페이지 크기 검증, 커서 인코딩/디코딩)이 들어 있었다.
- 사용자 결정: "지금 이야기한 거 진행할 것"(① #35 JpaStore 분리, ② #36 ApplicationConfiguration 정리, ③ 이번 PR). 유스케이스 분리와 `adapter/in·out` 패키지 구조 변경은 제외하기로 했다.
- 계약:
  1. DTO(record 10개)를 `adapter/web/dto` 패키지의 public 최상위 타입 한 파일씩으로 분리하고, 시간 포맷 헬퍼는 같은 패키지의 `ApiTime`(record가 아닌 final 유틸 클래스)으로 둔다. **타입 이름은 바꾸지 않는다**(springdoc의 스키마 이름이 클래스 단순 이름에서 나오므로 OpenAPI 명세가 달라지지 않게). 변환 정적 팩토리(`QuoteView.of`, `HistoryView.of`, `Result.ok`)는 유지한다.
  2. 시간 포맷(KST `yyyy-MM-dd HH:mm:ss`)은 `dto/ApiTime`으로 옮긴다.
  3. 페이지 크기 검증과 커서 인코딩/디코딩은 package-private `HistoryPaging`으로 옮기고 컨트롤러는 호출만 한다.
- 불변식: API 요청/응답/검증/오류 계약과 OpenAPI 명세(경로·스키마 이름) 불변. 기존 176개와 PostgreSQL 17개 테스트가 통과한다.
- 완료 기준: `ApiModels` 잔존 참조 없음, 페이징 단위 테스트, check·bootJar·postgresTest 통과, 실제 jar의 OpenAPI 명세를 변경 전과 비교, jar 스모크, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과와 직접 확인
- 이동 대응표: `ApiModels.{Signup,Login,QuoteInput,RequestInput,Result,LoginResult,QuoteView,QuoteResult,HistoryView,HistoryResult}` → `adapter/web/dto/*`(이름 그대로), `ApiModels.time`·`KST_TIME` → `dto/ApiTime.format`,
  `TransferController`의 `pageSize`·`encodeCursor`·`decodeCursor`와 상수 → `HistoryPaging`. `ApiModels`는 삭제(`grep` 잔존 0건). 어노테이션·주석·`toString`(redacted)·정적 팩토리는 그대로.
- 테스트: 신규 `HistoryPagingTest` 18개(페이지 크기 기본값·경계·거부 12건, 커서 null·왕복·비정규 표기·형식 오류·범위 경계). 기본 check 194개(기존 176 + 18), postgresTest 17개 통과, 실패·건너뜀 0. 기존 테스트는 수정 없이 통과.
- **OpenAPI 명세 비교(제가 직접)**: 변경 전 `dev` jar와 변경 후 jar의 `/v3/api-docs`를 받아 JSON 전체를 비교했다. `servers`의 포트(제가 다르게 띄운 값 18161/18171)를 빼면 경로·스키마·정보·보안 설정이 완전히 같다.
  implementer는 명세를 따로 비교하지 않았다고 보고했으므로 이 비교로 대체했다.
- 실제 jar 기동(스크래치 디렉터리): 가입 200, 견적 응답 형태·KST 시각, 이력 2건씩 페이지네이션(다음 커서 → 마지막 페이지 `nextCursor` 없음), 잘못된 커서 400, `size=0` 400, 오류 로그 0건.
- 한계: DTO 변환 위치는 각 DTO의 정적 팩토리를 유지했다(도메인 → DTO 변환이 DTO에 있음). 컨트롤러에 남은 `Idempotency-Key` 패턴과 quoteId 파싱은 이번에 옮기지 않았다.
