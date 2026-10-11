# hooks-json-format

- 요청: 사용자가 직접 포맷한 .codex/hooks.json을 새 PR에 반영한다.
- 기준: PR #5가 병합된 최신 origin/dev에서 별도 작업 트리를 분기했다.
- 변경: JSON 들여쓰기와 줄바꿈만 변경하며 훅 실행 내용은 동일하다.
- 검증: JSON 파싱, 기준 dev와 의미 동일성, git diff --check, 독립 내부 리뷰.
- 종료: 하네스 커밋과 새 dev PR 생성. 승인·병합은 사람.
