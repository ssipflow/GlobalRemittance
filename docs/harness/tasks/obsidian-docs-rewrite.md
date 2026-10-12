# obsidian-docs-rewrite

- 배경: 사용자가 옵시디언에 동기화된 앱 문서가 마음에 들지 않는다고 했다(구체 사유는 말하지 않음).
  전체를 읽고 구조 문제를 진단했다: architecture.md의 접수 흐름 한 문단에 집계·락·페이지네이션·인덱스가
  뭉침, design.md가 결정/이유/대가 구조 없이 패치가 쌓인 형태이고 페이지네이션 결정이 한계 목록에 묻힘,
  api.md 오류 표의 HTTP 순서 혼재와 요청 예시 부재, overview.md의 환경변수 누락(REMITTANCE_LOCK_TIMEOUT_MS).
- 범위: docs/application 4종의 구조와 서술만. 코드·동작·API 계약은 변경하지 않는다. 미구현을 구현으로 쓰지 않는다.
- 완료 기준: 모든 사실을 코드와 대조, 독립 리뷰, 하네스 검증(스크립트 테스트·diff check),
  docs/application 갱신 후 dev PR, 이후 옵시디언 sync로 네 노트 갱신 확인.
- 종료: 커밋·push·dev PR. 승인·병합은 사람. 이후 개발(백로그)로 복귀.

## 변경 요약
- overview: '한눈에 보기' 흐름 표, 읽는 순서, 환경변수 표(기본값 열, REMITTANCE_LOCK_TIMEOUT_MS 추가).
- architecture: 인증·견적·접수·이력 흐름을 각각 다이어그램으로 분리, 트랜잭션·동시성 경계 표,
  이력 조회 구조 절, erDiagram. 접수 흐름 한 문단에 뭉쳐 있던 내용을 분해했다.
- design: 모든 절을 결정/이유/대가로 통일하고 락 대기 한도·DB 집계·keyset을 독립 절로 승격.
- api: curl 호출 순서 예시, list의 파라미터/응답 필드 표/다음 페이지 읽기, 오류 표를 HTTP 순으로 정렬하고 발생 API 열 추가.
- 새로 쓴 서술(견적 오류 설명)을 코드(QuotePolicy)와 대조해 한 군데 바로잡았다. 동작·계약 변경 없음.
