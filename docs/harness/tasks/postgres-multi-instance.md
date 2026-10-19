# postgres-multi-instance

- 배경: 백로그 "운영 DB 검증"의 다중 앱 인스턴스 항목. 앞 단계(postgres-concurrency)는 한 앱 컨텍스트 안의 스레드 경쟁만 보았다.
  운영에서는 앱이 여러 대일 수 있고 인스턴스끼리는 JVM 락·캐시를 공유하지 않으므로, 보장이 DB 행 잠금만으로 지켜지는지 실제 PostgreSQL 16에서 검증한다.
- 계약(검증할 보장, 요청을 인스턴스 A·B에 번갈아/동시에 분산):
  1. 같은 견적을 A·B가 동시 접수 → 정확히 1건 성공, 나머지 409 ALREADY_REQUESTED.
  2. 서로 다른 견적(발급도 A·B 번갈아)의 분산 동시 접수가 합산 일일 한도(개인 1000 USD)를 넘지 않는다(2건 성공·나머지 400 LIMIT_EXCESS, 합계 796.4).
  3. 같은 Idempotency-Key·같은 견적을 A·B에 동시 → 모두 200이고 효과 1회(키 행 1개). 이후 어느 인스턴스로 다시 요청해도 같은 응답을 재현한다.
  4. 사용자 행 `FOR UPDATE`가 잡힌 동안 A·B 모두 503 LOCK_TIMEOUT(`Retry-After: 1`, 약 2초 대기), 해제 후 성공, 타임아웃 시도는 부분 상태 없음.
  5. A에서 가입·로그인한 JWT가 B에서도 유효하고, 위조 토큰은 양쪽 모두 401.
  6. 모든 결과는 DB(접수 행 수, 합계, 키 행 수)로 직접 단언한다.
- 불변식: 기본(H2) 실행·API·오류 계약 불변. main 코드 변경 없음. 기본 테스트 수 불변(태그 `postgres`는 기본 `test`에서 제외).
- 완료 기준: `postgresTest` 반복 통과, `check`(Spotless·Checkstyle 포함) 통과.

## 구현 결과와 직접 확인
- 구현: `PostgresMultiInstanceTest`(태그 `postgres`, 6개). 컨테이너 하나에 `SpringApplicationBuilder`로 `RemittanceApplication` 컨텍스트 2개(프로파일 test+postgres,
  `--server.port=0`, 명령줄 인자로 datasource를 컨테이너 URL로 덮어씀)를 띄우고 `java.net.http.HttpClient`로 실제 HTTP 요청을 보낸다. 두 컨텍스트는 빈·Hikari 풀·서블릿 서버가 별개다
  (첫 테스트가 DataSource가 다른 객체이고 접속 주소가 다르며 같은 PostgreSQL/DB를 보는지 단언, 서블릿 서버 분리는 서로 다른 포트로 확인).
- Flyway: A가 마이그레이션을 마친 뒤 B를 순차 기동했다. 동시 기동 경쟁은 변수를 줄이려 검증하지 않았다(Flyway의 DB 어드바이저리 락에 위임, 이 작업 범위 밖).
- JWT: 서명 키는 `remittance.jwt-secret`(운영은 `REMITTANCE_JWT_SECRET`) 설정에서 Base64로 읽고 `JwtTokens`는 상태가 없다. 따라서 인스턴스가 같은 키 설정을 쓰면 토큰이 호환된다.
  이 테스트는 test 프로파일의 같은 값을 두 인스턴스가 읽는 전제이며, 운영에서도 전 인스턴스에 같은 비밀을 주입해야 한다는 배포 요건이다(키가 다르면 401).
- 동시성: 스레드마다 짝/홀로 A·B에 배정하고 CountDownLatch 시작 게이트로 동시에 푼다. 고정 sleep 없음.
- 잠금 시나리오: 앱 풀과 무관한 평범한 JDBC 연결이 `select ... for update`로 사용자 행을 쥐어 "다른 인스턴스의 진행 중 접수 트랜잭션"을 결정적으로 대신한다.
  실제 접수 요청을 중간에 멈추는 훅은 main에 없어 추가하지 않았다.
- 검증: `postgresTest --rerun-tasks` 3회(+Checkstyle 수정 후 1회) 전부 통과, 전체 26개(기존 20 + 이번 6) 실패 0·건너뜀 0. `check` 통과.
- 발견: 이번 검증에서 main 코드 결함은 드러나지 않았다. 행 잠금 직렬화는 인스턴스가 달라도 동일하게 동작한다.
- 한계: 일일 한도 시나리오는 KST/UTC 날짜 경계 직전에 실행하면 "오늘"이 바뀌어 흔들릴 수 있다(기존 PostgresConcurrencyTest와 같은 성질). 같은 JVM 안의 두 컨텍스트라 네트워크 분리·프로세스 단위 장애·시계 차이는 재현하지 않는다. 위 잠금 시나리오의 잠금 보유자는 앱 요청이 아니라 외부 JDBC 연결이다.
  Flyway 동시 기동 경쟁, 3대 이상, 고부하·장시간 경쟁, 로드밸런서 경유는 검증하지 않았다.
