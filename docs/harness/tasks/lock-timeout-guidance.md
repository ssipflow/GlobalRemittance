# lock-timeout-guidance

- 배경: 동시성 작업(PR #10) 때 범위 밖 제안으로 남긴 두 가지다. (1) 503 LOCK_TIMEOUT에 재시도 안내가 없다. (2) `REMITTANCE_LOCK_TIMEOUT_MS`가 H2 URL에 그대로 들어가
  빈 값·문자가 섞이면 DB 접속 실패처럼 보이는 엉뚱한 오류가 난다.
- 사용자 결정: 후속 후보 진행. 세부 계약은 아래 기본안으로 정했고 PR 본문에 밝힌다.
- 계약:
  1. LOCK_TIMEOUT 503 응답에만 `Retry-After: 1`(고정 1초). 본문·상태·다른 오류는 불변.
  2. `remittance.lock-timeout-ms`는 `[1-9][0-9]{0,4}`(선행 0 없는 숫자)이고 1~30000일 때만 허용. 위반(빈 값·공백·부호·소수·단위·선행 0·0·30001+)은 DB 연결 전에 기동 거부.
     상한 30000은 이 프로젝트의 판단(대기 요청이 Tomcat 스레드와 DB 연결을 오래 붙잡는 것을 막음)이다.
  3. 기동 거부는 FailureAnalyzer가 한국어 설명·조치를 출력한다. 값은 ASCII 출력 가능 문자만 남기고 20자로 절단해 보여 준다.
- 불변식: 유효 설정과 모든 기존 응답은 그대로이고, 잘못된 설정은 DB 파일을 만들거나 열기 전에 거부된다.
- 완료 기준: Retry-After 존재·부재 검증, 설정 허용·거부 경계 단위 테스트, spring.factories 등록 검증, 실제 기동으로 잘못된 값 거부 출력 확인,
  기존 테스트 전부 통과, Java 17 check·bootJar, 문서 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과와 직접 확인
- 구현: `ApiExceptionHandler`(LOCK_TIMEOUT에만 `Retry-After: 1`), bootstrap의 LockTimeoutEnvironmentPostProcessor·InvalidLockTimeoutException·LockTimeoutFailureAnalyzer,
  spring.factories 등록. 리뷰 후 정규식을 `[1-9][0-9]{0,4}`로 좁혀 선행 0("05", "00005")도 거부한다.
- 실제 기동(스크래치 작업 디렉터리, 사용자 `./data` 미사용): `abc`·빈 값·`0`·`30001`·`5s`·`05`는 모두 `APPLICATION FAILED TO START`에
  설명과 조치를 출력하고 작업 디렉터리에 `data/`가 생기지 않았다(DB 연결 전 거부). `3000`·`30000`은 정상 기동. 404 응답에는 `Retry-After`가 없다.
- 자동 테스트: LockTimeoutSettingsTest(허용 3개·거부 16개 값, 속성 없음, 분석기 출력·정리·무관 예외 null, 기동 실패, spring.factories 등록),
  RemittanceApiTest(503에 `Retry-After: 1`, 400·409에는 없음). 기동 실패 테스트는 "DB 연결 전 거부"를 주장하지 않는다(그 부분은 위 수동 확인).
- 독립 리뷰: 차단 결함 없음. 지적한 테스트 보강·정규식·문서 과장(멱등성 키 조건)을 반영했고 반영분은 `verify`로만 확인했다.
- 한계: `Retry-After: 1`은 고정값이며 실제 경합 시간을 반영하지 않는다. 상한 30000은 이 프로젝트의 판단이다. 잠금 경합을 실제 운영 DB에서 확인한 것은 아니다.
