# rate-freshness-policy

- 배경: 백로그 1번 "환율 공급 어댑터". 사용자 결정으로 실제 외부 공급처는 붙이지 않는다. 그러나 `Ports.Rates.toKrw(currency)`는 값만 반환해
  환율의 지연이나 조회 실패를 표현하지 못한다. 실제 공급처가 붙기 전에 정책과 포트 계약을 먼저 고정해 둔다.
- 사용자 결정: 권장 순서(2순위)로 진행. 세부 정책은 내가 제안한 기본안을 사용자가 수락했다("계속 진행해").
- 계약:
  1. `RateSnapshot(toKrw, asOf)`. `Rates.rate(currency)`가 스냅샷을 돌려준다. 공급 실패는 어댑터가 `RATE_UNAVAILABLE`로 알린다.
  2. 견적 발급 시 대상 통화·USD 스냅샷을 검사한다. 기준 시각과 현재의 차이(절댓값)가 허용 지연(기본 60초, 1~3600)을 넘으면 `RATE_STALE`. 경계(차이 == 허용치)는 신선.
     미래 기준 시각도 허용 범위를 넘으면 stale(시계 어긋남 방어). 대상이 USD면 스냅샷을 한 번만 조회한다.
  3. `RATE_UNAVAILABLE`/`RATE_STALE`은 HTTP 503, WARN 로그에는 코드만 남긴다. 직전 값을 몰래 쓰지 않는다(금융 서비스에서 안전한 쪽).
  4. 이미 발급된 견적은 10분 고정 환율 계약을 유지하고 접수는 환율을 조회하지 않으므로 환율 장애의 영향을 받지 않는다.
  5. 모의 공급처(`source=simulated`)는 설정으로 지연·장애를 주입한다. 기본 `configured`는 외부에서 보기에 동작이 바뀌지 않는다.
- 불변식: 기본 설정의 견적·접수·이력 결과와 기존 오류 계약 불변. 시크릿·개인정보 로그 없음.
- 완료 기준: 정책 경계 단위 테스트, 모의 공급처 API 테스트(장애/지연/경계/잘못된 설정/발급 견적 접수), 기본 check·bootJar·postgresTest 통과, 실제 jar 기동으로 정상/지연/장애 확인, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.
- 범위 밖(후속 후보): 견적에 환율 기준 시각 저장(감사용, 스키마 변경 필요), 실제 외부 공급처 어댑터(타임아웃·재시도·서킷 브레이커·트랜잭션 밖 호출), 다중 공급처.

## 구현 결과와 직접 확인
- 구현: `RateSnapshot`(도메인), `Ports.Rates.rate()`, `TransferUseCases.quote`의 신선도 검사, `ConfiguredRates`(Clock), `SimulatedRates`, 설정 선택/검증(`ApplicationConfiguration`),
  503 매핑·WARN 로그, OpenAPI 503 설명, 설정 키 4개.
- 테스트: `RatePolicyTest` 9개, `RatesApiTest` 13개. 기본 `check` 128개(기존 106 + 22), `postgresTest` 17개 통과, 실패·건너뜀 0. 기존 테스트는 수정 없이 통과.
- 실제 jar 기동(스크래치 작업 디렉터리): 기본 200, 모의 지연 120초 503 `RATE_STALE`, 지연 30초 200, 모의 장애 503 `RATE_UNAVAILABLE`(WARN 로그에 코드만, `Retry-After` 없음),
  `REMITTANCE_RATES_MAX_AGE_SECONDS=0`·`REMITTANCE_RATES_SOURCE=live`는 설정 키가 든 메시지로 기동 거부.
- 발견: 잘못된 환율 설정은 DB 초기화 이후(`transfers` 빈 생성 시)에 거부되어 거부 전에 `data/`가 만들어진다(잠금 대기 설정의 DB 연결 전 거부와 다르다). 문서에 적었다.
- 한계: 실제 공급처 없음, 견적에 기준 시각 미저장, 모의 장애 모드의 통화 검증 순서, 시계 동기화 미보장. `api.md`의 오류 표·견적 설명은 이번 작업 범위에 포함해 갱신했다.
