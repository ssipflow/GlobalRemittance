# history-pagination

- 배경: 백로그 1번. GET /transfer/list가 전체 접수 이력을 반환해 거래가 쌓이면 응답과 메모리가 무한히 커진다.
- 사용자 결정: 호환은 선택 파라미터(size, cursor), 방식은 keyset 커서. 정렬은 접수시각·ID 내림차순 유지.
- 계약: size 기본 50, 허용 1..100, 범위 밖/비숫자는 400 INVALID_REQUEST. cursor는 불투명 문자열이며
  잘못된 값은 400 INVALID_REQUEST. 응답은 기존 필드를 유지하고 nextCursor를 추가한다(마지막 페이지는 null).
  기존 클라이언트는 첫 페이지만 받는다(전체 반환 동작은 사라진다).
- 불변식: todayTransferCount/UsdAmount는 어느 페이지를 요청해도 같은 오늘(KST) 전체 집계다.
  건수와 합계는 한 쿼리로 구해 서로 모순되지 않는다. 페이지 사이에 새 접수가 생겨도 중복·누락이 없다.
- 완료 기준: 페이지 경계·동시 접수 중 중복/누락 없음·동일 접수시각 tie-break·잘못된 커서/size·KST 자정 집계
  회귀 검증, Java 17 check·bootJar, docs/application 갱신, backlog 1번 완료 표시.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과
- 포트: Quotes.requestedPage(keyset, limit+1로 다음 페이지 판단), requestedDailyTotal(count+sum 한 쿼리).
  전체 조회 requestedQuotes와 today 헬퍼는 제거했다. requestedUsdTotal은 접수 한도 검사용으로 유지.
- 웹: size는 문자열로 받아 직접 검증(공통 JSON 오류 유지). 커서는 "초.나노.ID" URL-safe Base64이며
  재인코딩이 입력과 같아야 통과하는 엄격 검증. 조회는 항상 인증 사용자로 제한되어 커서는 권한과 무관.
- 비용: 임의 페이지 점프 불가. 목록과 오늘 집계는 별개 쿼리라 접수와 겹치면 시점이 다를 수 있다
  (이를 직접 검증하는 동시성 테스트는 없다). 호환 변경: 파라미터 없는 요청은 전체가 아닌 첫 50건.
- 소유 파일 목록은 이번에 정확히 지켰다(목록 밖 수정 없음).

## 내부 리뷰 반영
- 리뷰어가 극단적 초 값 커서가 DB 바인딩에서 500을 낼 수 있다고 지적했다. 수정 전 재현에서는 H2가
  오류 없이 200을 반환해 확정 결함은 아니었다. DB에 따라 달라질 수 있어 방어적으로 커서 초 값을
  0..253402300799(9999-12-31T23:59:59Z)로 제한하고 경계 테스트를 추가했다.
- size는 숫자만 허용(+5 거부), 테스트의 바이트 변환은 UTF_8 명시, 인덱스 서술은 (user_id, requested_time)
  기준으로 정정. state.md의 '이력 페이지네이션 없음' 서술 제거.
