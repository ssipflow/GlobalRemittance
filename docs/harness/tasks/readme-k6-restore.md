# readme-k6-restore

- 요청: README 축약 후 k6 설명이 보이지 않는다는 사용자 지적에 따라 실행 섹션을 복원한다.
- 기준: dev cf15df7, feature/readme-k6-restore. README와 작업 기억만 수정한다.
- 변경: 램프업·스파이크·5분/2시간 소크 명령, 로컬 앱·DB와 k6 전제, 테스트 데이터 분리, 결과 지표 해석, 과거 2시간 관찰 요약과 한계, 상세 가이드·검증 근거 링크.
- 근거: k6/transfer-flow.js·spike.js·soak.js·lib.js, docs/portfolio/validation.md 및 과거 postgres-failure-modes 작업 기록.
- 검증: git diff --check 등록, 독립 리뷰의 스크립트/관찰값 대조와 verifier의 링크·앵커·EOF/셸 문법 정적 확인. 앱·DB·k6·회귀 테스트 새 실행 없음.
- 최종 snapshot에서 리뷰·verify 후 finish·publish한다. export 대상 앱 설명이나 구조를 바꾸지 않아 옵시디언 본문 재동기화는 하지 않는다.
