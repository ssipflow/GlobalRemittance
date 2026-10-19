# k6-load-test

- 배경: 백로그 "운영 DB의 잠금·장애 복구 검증" 이후 부하 관점의 확인. PostgreSQL 동시성·다중 인스턴스·재시작 검증(PR #27~#50)은 정확성 중심이었고, 램프업·급증·지속 부하에서 응답시간과 오류 분포가 어떤지는 측정하지 않았다.
- 계약(스크립트가 판정하는 기준, 출처 `docs/application/api.md`):
  1. 계약 밖 응답(500 `UNKNOWN_ERROR`, 401, 정의되지 않은 코드)은 0건.
  2. `409 ALREADY_REQUESTED`, `400 LIMIT_EXCESS`, 정의된 503(`LOCK_TIMEOUT`, `DB_UNAVAILABLE`, `RATE_*`)은 정상 응답으로 별도 집계하고 실패로 세지 않는다.
  3. 같은 `Idempotency-Key` 재시도는 200 재현, 키 없는 재시도는 409(`invariant_violations` 0건).
- 불변식: 앱 main 코드·API·기본 `check` 불변. `k6/`는 Gradle(Spotless/Checkstyle) 대상이 아니다. CI에는 연결하지 않는다.
- 완료 기준: 세 시나리오 `k6 inspect` 통과, 로컬에서 실제 실행해 임계값 통과, 실행 가이드(README), 한계 기록.

## 구현 결과와 직접 확인
- 구현: `k6/lib.js`(사용자 가입·로그인, 견적·접수·이력, 응답 분류·메트릭), `k6/transfer-flow.js`, `k6/spike.js`, `k6/soak.js`.
  VU마다 고유 사용자(`k6load-<RUN_ID>-vu<N>-u<순번>@example.com`)를 만들고, 소액(1만~3만 KRW)만 접수하다 `LIMIT_EXCESS`가 나오면 새 사용자로 교체한다. 접수의 70%는 `Idempotency-Key`를 붙이고 그 중 일부는 같은 키로, 일부는 키 없이 재시도해 200 재현·409를 확인한다.
  `http.setResponseCallback(expectedStatuses(200,400,409,503))`로 계약 상 상태는 `http_req_failed`에서 제외하고, 의미 판단은 `classify`(상태+`resultMsg`)가 한다. spike/soak는 구간(phase) 태그로 구간별 지연 임계값을 건다.
- 환경: 이 Mac, 앱 1개(`./gradlew bootRun`, 기본 postgres 프로파일), PostgreSQL 16 컨테이너 1개(기존 compose 볼륨 사용), k6 v1.1.0, 클라이언트도 같은 PC. 수치는 이 조건의 단일 실행 관찰이다(반복 분산은 측정하지 않았다).
- 관찰(마지막 실행 기준, 모든 임계값 통과):

| 시나리오 | 부하 | 처리량 | 지연(p95) | 오류 분포 |
| --- | --- | --- | --- | --- |
| transfer-flow | 0 -> 30 VU 램프업, 약 115초 | 약 125 req/s, 반복 6242 | quote 9.4ms, request 10.4ms, list 8.2ms, signup 395ms, login 371ms | 예상 밖 0, 5xx 0, 정의된 거절(409/400) 873, 정의된 503 0 |
| spike | 5 -> 100 VU 급증(5초), 20초 유지, 복귀 | 약 142 req/s, 반복 5784 | 급증 중 request 23ms, 복귀 후 quote 15.3ms·request 16.8ms, 회복 구간 healthy 100% | 예상 밖 0, 5xx 0, 거절 791, 503 0 |
| soak | 20 VU, 5분 | 약 124 req/s, 요청 37145 | early/mid/late request 13.3/12.3/12.0ms, quote 12.3/11.5/11.6ms. 전체 p99 349ms(가입·로그인 BCrypt 포함) | 예상 밖 0, 5xx 0, 거절 2351, 503 0 |

- 임계값(관찰값에 큰 여유를 둔 상한): quote/request/list p95 < 150ms, signup/login p95 < 1000ms(transfer-flow), `unexpected_responses` 0, `invariant_violations` 0, 5xx 비율 < 1%, `http_req_failed` < 0.1%. spike는 급증 중 request p95 < 1000ms, 복귀 구간 p95 < 150ms·healthy 비율 > 99%. soak는 세 구간 모두 같은 p95 상한과 late healthy > 99%.
- 발견: 부하에서 500 등 결함은 관찰되지 않았다. spike 급증 순간 가입(BCrypt) 요청이 최대 약 4~5초까지 느려졌다(요청 단위 max). 급증 구간 p95에는 영향이 작았고 복귀 구간은 정상으로 돌아왔다.
- 초기 실행에서 `flow_success_rate`가 의도한 409(재시도 검증)를 감점해 94%로 나온 설계 오류를 발견해 정의된 응답을 건강한 것으로 보는 `flow_healthy_rate`로 바꿨다.

## 한계
- 로컬 단일 인스턴스·단일 PostgreSQL, 클라이언트와 서버가 같은 CPU를 공유한다. 운영 성능·용량 보증이 아니며 임계값은 이 Mac 기준이다.
- 처리량은 VU 수와 think time(0.2~0.5초)이 정한다(최대 부하 탐색이 아님). 정의된 503(`LOCK_TIMEOUT` 등)이 실제로 발생하는 경합 수준까지는 올리지 않아, 부하 중 503 경로는 관찰하지 못했다. 같은 사용자에 대한 고경합은 `PostgresConcurrencyTest`가 다룬다.
- soak는 5분이며 그보다 긴 시간의 메모리·연결 누수는 확인하지 않았다. 악화 판정은 구간별 절대 상한(150ms, 관찰값 8~17ms 대비 약 9~18배 여유)이라 급격한 악화만 잡고 완만한 악화는 놓치며 early 대비 비율 임계값은 아니다(k6 임계값이 두 메트릭 비교를 지원하지 않는다).
- 부하 테스트 사용자는 로컬 DB에 남는다(삭제하지 않음). 환율은 기본 설정 환율이라 `RATE_STALE/UNAVAILABLE` 경로는 부하에서 발생하지 않는다. CI 연동은 하지 않았다.

- 추가 한계: 키 없는 재시도는 200/409(정의된 503 제외) 외 응답이면 `invariant_violations`로 센다(중복→만료→한도 판정 순서에 의존). spike의 phase 경계는 setup 시각 기준 벽시계라 k6 시작과 수 초 어긋날 수 있다. 가입·로그인 지연은 spike/soak에서 임계값을 두지 않았다. `k6 inspect` 4개 파일 통과를 확인했다.
