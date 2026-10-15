# concurrency-hardening

- 배경: 코드베이스 분석에서 확인한 동시성 한계 세 가지를 보완한다. 이 작업은 Claude가 처음 수행한다.
  1) 락 대기 타임아웃이 UNKNOWN_ERROR(500)로 노출될 수 있다.
  2) 일일 합계가 접수 이력 전체를 메모리로 읽어 사용자 락을 쥔 채 O(n)이다.
  3) history()가 접수와 같은 쓰기 락을 잡아 조회가 접수와 서로 막힌다.
- 변경 방침: 사용자 행 비관적 락은 접수의 직렬화 지점으로 유지한다. 합계는 KST 하루 구간
  [당일 00:00, 익일 00:00)을 Instant로 변환해 DB SUM으로 구한다. history는 한 번의 조회 결과에서
  목록과 오늘 집계를 모두 계산하므로 락 없이도 자기 일관적이다.
- 완료 기준: 동시 접수 2종·한도 경계·KST 자정 회귀 유지, 락 타임아웃 오류 테스트 추가,
  history가 접수를 막지 않음을 검증, Java 17 check·bootJar, docs/application 갱신.
- 환경: JAVA_HOME은 Corretto 17로 검증한다.
- 종료: 커밋·작업 브랜치 push·dev PR, 이후 옵시디언 sync. 승인·병합은 사람.

## 구현 중 확인한 사실
- H2에서 jakarta.persistence.lock.timeout 힌트는 무시된다(for update에 wait가 붙지 않음).
  대기 한도는 JDBC URL LOCK_TIMEOUT(remittance.lock-timeout-ms, 기본 5000ms, 테스트 1000ms)으로 정한다.
- 락 타임아웃이 나면 커넥션이 broken 처리되어 롤백도 실패하고 원래 BusinessException을 덮었다.
  TransactionalWork가 롤백 실패 시 원래 BusinessException을 전달하도록 보완했다.
- 소유 파일 목록에 TransactionalWork가 빠져 task를 block 후 재등록했다(사용자 승인, 코드·Git 이력 영향 없음).
- 남은 한계: history 페이지네이션 없음, 멱등성 키 없음, 락 타임아웃 설정은 H2 URL 옵션 의존.
- 운영 기본 락 대기 한도를 10000ms에서 5000ms로 줄였다(의도된 변경, REMITTANCE_LOCK_TIMEOUT_MS로 조정).
  테스트 값은 느린 환경의 가짜 503을 피하려 2000ms로 두고 시간 임계값을 그에 맞췄다.
- 내부 리뷰 비블로킹 제안 중 Retry-After 헤더와 환경변수 검증은 이번 범위에서 제외한다.
