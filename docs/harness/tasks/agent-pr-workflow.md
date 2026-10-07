# 최신 에이전트 역할과 PR 완료 단계 반영

## 요구와 범위

사용자가 누적 맥락을 하네스에 반영하도록 요청했다.
로컬 에이전트의 역할은 구현·검증·커밋·작업 브랜치 push·dev PR 생성까지다.
로컬 리뷰는 내부 개선 루프에만 사용하며 PR 리뷰/코멘트를 게시하지 않는다.
GitHub AI가 PR 리뷰를 담당하고 사람만 승인/병합한다. test는 의미상 브랜치다.

저장소의 아키텍처/브랜치 정책 문서는 이번에도 바꾸지 않는다.
AGENTS.md, .codex/instructions/workflow.md, 역할별 에이전트, 개인 선호,
실행 하네스와 작업 기억에 현재 지시를 반영한다. 기존 PR #1도 변경하지 않는다.

## 구현과 검증 기준

- finish: 독립 내부 리뷰/실제 검증을 통과한 변경만 커밋하고 committed로 기록.
- publish: 저장한 브랜치/HEAD/깨끗한 snapshot과 GitHub origin/명시적 repo 일치 확인.
  origin의 fetch URL 외에 실제 push URL도 검사하고 다중 push 대상은 거부한다.
- 작업 브랜치만 일반 push. dev 대상 PR만 생성하고 실제 head SHA를 확인해 completed로 기록.
- 이미 열린 PR은 재사용. 사람에 의해 닫거나 병합된 PR은 자동 재생성하지 않음.
- 실패 시 committed 유지. Stop은 커밋 이후에도 PR 단계까지 계속하며 재시도 상한을 유지.
- 커밋 후 중단 사유는 block의 실행 상태에만 기록. 파일 수정이 필요하면 reopen으로
  내부 리뷰/검증 단계를 복원하고 기존 커밋을 보존한 채 후속 커밋을 추가한다.
- PR 코멘트/리뷰/approve/merge/auto-merge 호출 없음. 실제 PR은 마지막 검증으로 확인한다.
- 테스트는 임시 Git 저장소와 mock 원격 호출을 사용하며 실제 원격 변경은 하지 않는다.

검증 명령: `python3 -m unittest discover -s scripts/tests -v`, `git diff --check`.
변경한 에이전트 TOML은 별도로 파싱 확인한다. 앱 테스트는 이번 변경 범위가 아니다.

## 이어갈 작업

feature/test-isolation: 테스트 DB를 메모리 H2로 격리하고 실제 환율 API 호출을
고정 응답으로 대체한다. Java 실행 환경을 확인하고 전체 테스트의 성공/실패 기준선을 기록한다.
이 작업 PR의 사람 확인/병합 후 최신 dev에서 분기한다. PR URL은 .harness/task.json과
GitHub에서 확인하고 다음 작업이 tracked state를 갱신한다.
