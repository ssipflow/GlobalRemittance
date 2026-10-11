# 에이전트 실행 범위와 현재 합의

이 파일은 다음 세션에서도 유지할 에이전트 운영 지침이다.
저장소의 아키텍처·브랜치 정책 문서를 다시 정의하는 대신, 현재 사용자가 정한
에이전트의 실행 범위를 명시한다. 이전 문서와 충돌하면 이 최신 지시를 따른다.

## 역할과 종료 지점

- Coordinator: 구현 조정 → 로컬 개선 루프 → 검증 → 기억 갱신 → 커밋 →
  작업 브랜치 push → dev 대상 PR 생성과 URL 보고까지만 수행한다.
- implementer: 할당된 파일 구현과 회귀 검증. 원격 PR 작업을 수행하지 않는다.
- reviewer: 읽기 전용으로 Coordinator에게 내부 개선점을 반환한다.
  PR에 리뷰·코멘트를 게시하지 않는다. 내부 리뷰 통과는 GitHub의 Approve가 아니다.
- verifier: 등록된 검증을 실행하고 실제 결과를 반환한다.
- GitHub AI: PR에서 코드 리뷰와 코멘트를 담당한다. 자동 연결 여부는 따로 확인해야 한다.
- 사람: 최종 판단, 승인과 병합. AI는 approve, merge, auto-merge, 보호 규칙 우회를 하지 않는다.

Coordinator도 로컬 리뷰 결과를 GitHub에 게시하지 않는다. `gh pr review`,
`gh pr comment`, reviews/comments API, `@codex review` 코멘트는 실행 범위에 포함하지 않는다.
PR 본문에는 변경 내용과 검증 근거를 기록할 수 있다. 사람이 리뷰 요청을 맡거나
GitHub AI의 자동 리뷰 설정을 사용한다. 사람의 추가 지시는 현재 대화에서 판단한다.

## 브랜치 흐름

dev에서 `feature/<feature-name>` 또는 `hotfix/<fix-name>`을 분기한다.
dev에 직접 병합/push하지 않고 **작업 브랜치 → dev PR**로 전달한다.
test는 의미상 테스트 배포 원본이다. 실제 서버와 CI/CD는 없으며, 사람의 별도
요청 없이 test 병합을 필수 단계로 실행하지 않는다. main은 운영 배포 원본이라는 가정이다.
dev → main 역시 사람이 최종 승인/병합할 PR로 승격한다. 일반 작업은 dev 대상 PR에서 끝난다.

## 기억과 현재 환경

- 프로젝트 목표: Goals Of Project 요구사항 기반 송금 서비스 전면 재구현.
  포트폴리오 품질과 클린 아키텍처 기준을 유지하고 업무 규칙·설계 이유에 한글 주석을 단다.
- 공통 원칙과 개인 선호를 분리한다. 선호는 명시적인 새 요청에 따라 갱신한다.
- 작업 기억은 state/backlog/작업별 기록, 실제 상태는 Git과 `gh`로 대조한다.
- gh 설치와 GitHub 로그인은 확인했다. 토큰 값은 저장하거나 출력하지 않는다.
- GitHub 비공개 저장소의 보호 규칙 화면에 요금제상 강제 불가 안내가 있었다.
  PR만 사용한다는 에이전트 지침이 서버 측 직접 push 차단을 의미하지는 않는다.
- Codex GitHub 자동 리뷰 연결은 아직 확인되지 않았다. PR #1에서 로컬 코멘트
  게시 테스트는 성공했으나 사용자는 이후 그 코멘트를 원하지 않는다고 정정했다.

PR이 열리면 local task는 완료이며 실제 GitHub 상태를 확인한 URL을 반환한다.
리뷰 응답을 기다리거나 승인/병합을 대신 수행하면서 작업 범위를 늘리지 않는다.
커밋 전에는 tracked 작업 기억을 갱신한다. 커밋 후 중단 사유는 block으로
로컬 실행 상태에 기록하고 publish를 재시도한다. 커밋 후 파일 수정이 필요하면
reopen으로 내부 검증 단계를 복원해 후속 커밋을 추가한다. 이력을 재작성하지 않는다.

## 옵시디언 문서화

작업 시작과 기억 갱신 시 `.codex/instructions/obsidian.md`를 읽는다.
프로젝트 전용 폴더의 연결 설정은 Git에서 무시되는 `.harness/obsidian.json`에 둔다.
작업 시작·중단·커밋 전·PR 생성 후 명시적으로 단방향 sync한다.
실행 명령과 연결 상태는 `docs/harness/obsidian.md`를 따른다. Vault Git 작업은 하지 않는다.
사용자가 Goals Of Project를 새 목표로 지정하고 기존 애플리케이션 전면 교체를 승인했다.
구현 기준은 `docs/requirements/project-goals.md`, 결정은 `docs/decisions/remittance-rebuild.md`다.
과거 개선 백로그는 자동 실행하지 않는다. 기존 하네스·지침·Git 이력은 보존한다.
