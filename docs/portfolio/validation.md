# 검증 범위와 결과의 해석

기준 구현은 `dev 69d2138`(PR #58 반영)이다. 이 문서는 검증 방법과 기존 기록을 정리하며 현재 문서 커밋의 앱 테스트 통과 인증을 대신하지 않는다. 작업마다 실제 실행 명령·커밋·환경·결과는 하네스 작업 기록과 PR에서 확인한다.

## 무엇을 어떤 방법으로 확인했나

| 영역 | 검증 방법·근거 | 확인 범위와 한계 |
| --- | --- | --- |
| 금액·만료·KST·한도·인증·소유권·멱등성·이력 | 기본 JUnit, H2/API·도메인·보안 테스트 | 격리된 테스트 DB. 외부 금융기관·실시간 환율 아님 |
| 계층과 품질 규칙 | `ArchitectureTest`, Spotless·Checkstyle | 의존성·포맷·정적 규칙이며 설계 품질 전체의 증명은 아님 |
| PostgreSQL 동시성·잠금·롤백 실패 | PostgreSQL 16 Testcontainers, DB 행·합계 직접 단언 | 단일 앱의 6스레드 경쟁과 연결 종료로 만든 롤백 실패 |
| 다중 앱 인스턴스 | [기록](../harness/tasks/postgres-multi-instance.md), `PostgresMultiInstanceTest` | 같은 JVM의 독립 컨텍스트 2개, 별도 HTTP 서버·풀, 같은 DB. 별도 프로세스·호스트·시계 차이 아님 |
| 재시작·폭주·풀 고갈 | [기록](../harness/tasks/postgres-restart-load.md), `PostgresRestartLoadTest` | 정상 재시작, 풀 3·32스레드, 풀 고갈 별도 주입. 폭주 자체에서는 503 미관찰 |
| 비정상 종료·무응답 | [기록](../harness/tasks/postgres-failure-modes.md), `PostgresFailureModesTest` | SIGKILL·pause, 복구·부분 상태·같은 키 재시도 확인 |
| 네트워크 장애 형태 | [기록](../harness/tasks/network-partition.md), `PostgresNetworkPartitionTest` | Toxiproxy 블랙홀·reset·disable·지연·응답 방향 블랙홀. 로컬 TCP 프록시 범위 |
| 커밋 결과 불확실성 | [기록](../harness/tasks/commit-response-loss.md), `PostgresCommitResponseLossTest` | COMMIT 전 폐기·서버 커밋 후 응답 폐기, DB 반영과 재시도 효과 직접 확인 |
| 부하와 시간 경과 | [k6 기록](../harness/tasks/k6-load-test.md), [2시간 소크 기록](../harness/tasks/postgres-failure-modes.md) | 로컬 단일 앱·단일 DB. 응답 중심 검사, 최대 처리량 탐색 아님 |
| 하네스 게이트 | `scripts/tests/test_harness.py` 등 Python unittest | snapshot 변경·소유 범위·실패·publish 상태 처리. 리뷰 판단 품질 인증 아님 |

`postgresTest`는 Docker가 필요하고 기본 `check`에서 제외된다. 기본 검증 통과만으로 PostgreSQL 장애 시나리오까지 실행했다고 볼 수 없다. 로컬 다중 인스턴스 검증과 단일 앱 k6를 합쳐 다중 호스트 고부하 검증이라고 주장하지 않는다.

## 과거 실행에서 관찰한 수치

문서 분석 당시 남아 있던 JUnit XML은 기본 247개·PostgreSQL 40개, 실패·오류·건너뜀 0개였다. 이는 기존 산출물로서 새 실행이나 해당 커밋과의 일치를 증명하지 않는다. 정확한 현재 결과는 아래 명령으로 다시 확인한다.

2시간 소크는 macOS의 임시 `postgres:16-alpine` 컨테이너와 `bootRun` 앱 1개, 같은 PC의 k6 20 VU로 1회 실행했다. 기록상 2026-10-19 22:51부터 다음 날 00:51 KST까지 895,364요청(약 124/s), 전체 p95 10.4ms였다. `unexpected_responses`·`invariant_violations`는 0이고 정의된 거절은 54,581건이었다. 사용자 think time이 있어 처리량은 VU와 대기 시간에 영향을 받는다.

RSS는 141~251MB 범위, DB 연결 수는 샘플에서 11개로 유지됐다. 단발 최대 지연은 5.75초였고 원인은 분석하지 않았다. 완만한 악화를 놓칠 수 있는 넉넉한 절대 임계값이며 메모리 누수를 배제한 결과도 아니다. 운영 SLO·최대 용량·수 일 안정성으로 확대하지 않는다.

k6의 `invariant_violations`는 같은 키의 200 재현과 키 없는 재시도의 409 같은 **응답 계약**을 검사한다. 매 요청의 DB 중복·한도를 직접 확인하는 검사가 아니다. DB 행·합계 불변식은 별도의 통합 테스트에서 단언한다. 정상 거절과 정의된 503은 `unexpected_responses`·`http_req_failed`에서 제외하지만, 503은 `server_error_rate`에 포함되고 `flow_healthy_rate`에서는 실패로 센다. 계약상 정의된 응답과 서비스 건강도를 구분해 코드별 분포도 함께 본다.

## 다시 검증하기

Java 17, Docker, 저장소 Gradle wrapper를 사용한다. 실행 전 `git rev-parse HEAD`, `java -version`, Docker 버전을 함께 남긴다. 외부 키나 개인정보는 기록하지 않는다.

```sh
# 기본 품질·H2 회귀·실행 jar
./gradlew clean check bootJar --no-daemon
# Docker 기반 PostgreSQL 시나리오를 캐시 없이 실행
./gradlew postgresTest --rerun-tasks --no-daemon
# 앱 테스트와 별개인 하네스 회귀
python3 -m unittest discover -s scripts/tests -v
```

결과 XML은 `build/test-results/test`와 `build/test-results/postgresTest`에서 확인한다. k6는 실행 중인 로컬 앱과 DB가 필요하며 [로컬 실행 가이드](../application/local-guide.md#k6-부하-테스트)를 따른다. 재현마다 RUN_ID·앱/DB 구성·명령·요청 수·오류 분포·지연을 함께 기록하고 기존 관찰값과 새 결과를 구분한다. k6 사용자는 DB에 남으므로 개인 데이터와 섞이지 않는 DB를 쓴다.

## 아직 주장하지 않는 것

실제 금융기관 지급, 실제 시세 공급처, 운영 배포·CI/CD, 별도 앱 프로세스·호스트 장애, 관리형 DB·다른 PostgreSQL 버전, 모든 교착·네트워크 실패 조합은 검증하지 않았다. 데이터 보존·정리와 JWT 클레임 최소화는 사용자 결정으로 추가 구현 범위에서 제외했다. 요구사항과 선택은 [백로그](../harness/backlog.md) 및 결정 문서에서 확인한다.
