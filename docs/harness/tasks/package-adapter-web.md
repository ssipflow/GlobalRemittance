# package-adapter-web

- 배경: 패키지 재구성 5개 PR 중 2번째. 사용자가 "Web 이하에 Configuration, Model, Controller가 다 동일 계층에 있는 것이 마음에 들지 않는다"고 지적했다.
  `adapter/web`에 컨트롤러, 설정, 오류 처리, 필터가 한 패키지에 섞여 있었다(`dto`는 #37에서 이미 분리).
- 사용자 결정: 전체 패키지를 책임별로 재구성(1번째 #38에서 domain·application 완료).
- 계약:
  1. `adapter.web.controller`(TransferController, UserController, HistoryPaging), `adapter.web.error`(ApiExceptionHandler, JsonErrorController, ErrorDescriptions),
     `adapter.web.filter`(RequestIdFilter), `adapter.web.config`(JsonConfiguration, OpenApiConfiguration), 기존 `adapter.web.dto`.
  2. package-private 클래스(`HistoryPaging`, `ErrorDescriptions`)는 같은 하위 패키지의 사용처와 함께 옮겨 접근 범위를 넓히지 않는다. `HistoryPagingTest`도 같은 패키지로 옮긴다.
  3. 컴파일러가 못 잡는 문자열 FQN(`ObservabilityTest`의 `Class.forName("...ErrorDescriptions")`와 `RequestIdFilter` FQN)은 명시적으로 새 경로로 고친다.
- 불변식: 로직·API·OpenAPI 명세·필터 순서 불변. 기존 194개와 PostgreSQL 17개 테스트가 통과한다.
- 완료 기준: 옛 경로·옛 FQN 문자열 잔존 없음, check·bootJar·postgresTest 통과, OpenAPI 명세 비교, 요청 ID 헤더·오류 JSON 확인(jar 스모크), 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람. `finish`는 `GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=diff.renames GIT_CONFIG_VALUE_0=false` 환경변수로 실행한다. publish `--repo`는 ssipflow/GlobalRemittance.

## 구현 결과와 직접 확인
- 구조: `controller`(TransferController, UserController, HistoryPaging), `error`(ApiExceptionHandler, JsonErrorController, ErrorDescriptions), `filter`(RequestIdFilter), `config`(JsonConfiguration, OpenApiConfiguration), `dto`(기존). 옛 `adapter/web` 직속 파일 0개, 옛 FQN 문자열 0건(제가 `find`·`grep`으로 확인).
- 문자열 FQN 2곳(`ObservabilityTest`의 `Class.forName("...adapter.web.error.ErrorDescriptions")`, `new ...adapter.web.filter.RequestIdFilter()`) 수정. `HistoryPagingTest`는 `adapter/web/controller`로 이동.
- 접근 제어를 넓힌 곳 없음(`OpenApiConfiguration.BEARER_AUTH`는 이미 public, `TransferController`에 import 한 줄 추가). `HistoryPaging`·`ErrorDescriptions`는 사용처와 같은 패키지에 두어 package-private 유지.
- 테스트: 기본 check 194개(변동 없음), postgresTest 17개 통과, 실패·건너뜀 0. 기존 테스트는 위 2곳과 테스트 이동 외 수정 없음.
- **OpenAPI 명세 비교(제가 직접)**: 변경 전(#38 이후)과 변경 후 jar의 `/v3/api-docs`를 비교해 `servers` 포트를 빼고 완전히 같다.
- 실제 jar 기동(스크래치 디렉터리): 요청 ID 헤더(404·401 응답에 붙음, 클라이언트 값 `trace-abc.123`은 유지, 잘못된 값은 UUID로 교체), 오류 JSON(404 `NOT_FOUND`, 401 `UNAUTHORIZED`, 405 `METHOD_NOT_ALLOWED`), `swagger-ui.html` 302 → `index.html` 200, 가입·접수·이력 200, 오류 로그 0건.
- 한계: 이번 PR은 adapter/web만 다룬다. security·persistence·bootstrap·테스트 패키지는 다음 PR이다.
