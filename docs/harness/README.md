# Codex 하네스 사용법

메인 Coordinator + implementer / reviewer / verifier를 사용한다.
모델과 reasoning 설정은 부모 세션에서 상속한다. 동시 자식 에이전트 상한은 3이다.
최신 실행 범위는 `.codex/instructions/workflow.md`를 따른다. 로컬 reviewer는
내부 개선용이며 PR 코멘트를 게시하지 않는다. GitHub 리뷰는 GitHub AI,
승인·병합은 사람이 담당한다. 일반 작업은 dev PR 생성에서 끝난다.

## 활성화

저장소 루트에서 새 Codex 세션을 시작한다. 프로젝트 설정을 신뢰한 다음
CLI의 `/hooks`에서 이 저장소의 SessionStart / PostToolUse / Stop 훅을 검토하고 신뢰한다.
Codex는 새/변경된 훅을 신뢰하기 전에는 실행하지 않는다.
현재 세션에는 새 설정이 자동 반영된다고 가정하지 않는다.
공식 문서: https://learn.chatgpt.com/docs/hooks 및
https://learn.chatgpt.com/docs/agent-configuration/subagents

훅 명령은 Git 저장소 루트의 스크립트를 찾아 실행한다.
macOS/Linux, Python 3와 Git이 필요하다.

## 한 작업 실행

`docs/engineering/principles.md`, `preferences.md`, `branches.md`를 먼저 읽는다.
공통 원칙과 개인 선호는 별도로 관리한다. 아키텍처 컨벤션은 클린 아키텍처다.
dev 기반 작업 브랜치에서만 begin/finish할 수 있다:

```bash
git switch dev
git switch -c feature/test-isolation
```

begin은 현재 로컬 dev를 포함하는지 검사하고 그 기준 SHA를 저장한다.
이후에는 등록한 작업 브랜치를 검사한다. 다른 작업으로 dev가 진전하더라도
진행 중인 작업을 중단시키지 않으며 승격 단계에서 최신 통합 결과를 검증한다.

깨끗한 작업 트리에서 시작하며 파일은 디렉터리가 아닌 명시적 목록으로 지정한다.
state.md와 해당 작업 기록 파일도 소유 목록에 포함한다. 아래 파일명은 예시다.

```bash
python3 scripts/harness.py begin --id test-isolation \
  --goal '테스트를 사용자 홈 DB와 외부 API에서 격리' \
  --files src/test/resources/application-test.yaml docs/harness/state.md \
  --check './gradlew test'
python3 scripts/harness.py status
```

작업 중 목록에 없던 파일을 바꿔야 하면 `.harness/task.json`을 직접 고치지 말고 `own`으로 추가한다.
`begin`과 같은 규칙(저장소 상대 경로의 개별 파일, 디렉터리·내부 상태 거부)을 적용하고 추가 내역은
`ownership_additions`에 시각과 함께 남는다. 활성 작업에서만 쓸 수 있으며 리뷰·검증 증거는
내용 기반 snapshot에 묶여 있어 `own` 자체는 증거를 바꾸지 않고, 이후 파일이 바뀌면 다시 리뷰·검증해야 한다.
`begin`과 달리 이미 수정된 파일도 추가할 수 있으므로 사용자의 기존 변경을 포함하지 않도록 Coordinator가 확인한다.

```bash
python3 scripts/harness.py own --files src/test/java/com/nelson/remittance/example/ExampleTest.java
```

메인 에이전트에게 다음과 같이 지시한다:

> AGENTS.md를 따라 현재 작업을 implementer에게 위임해라.
> 완료 기준과 변경 전 기준선을 기록하고, reviewer와 verifier의 독립 결과를 받아라.
> 블로킹 결함은 수정 후 다시 검증하고 작업 기억을 갱신해라.
> 최종 snapshot에 대한 내부 리뷰와 검증이 통과하면 finish로 커밋하고 publish로 dev PR을 만들어라.

메모리 파일까지 최종 수정한 후 status의 snapshot으로 독립 리뷰를 요청한다.
리뷰 통과 근거와 실제 검증 결과를 기록한다:

```bash
python3 scripts/harness.py attest-review --snapshot '<status의 snapshot>' \
  --evidence '<reviewer 결과 요약과 참조; 미해결 블로킹 결함 없음>'
python3 scripts/harness.py verify
python3 scripts/harness.py finish --message 'test: isolate remittance test environment'
python3 scripts/harness.py publish --repo ssipflow/Moin-GlobalRemittance \
  --title 'test: isolate remittance test environment' --body-file /tmp/pr-body.md
```

`--check`는 사용자가 신뢰하는 로컬 셸 명령만 지정한다. 실제 실행되므로
외부 콘텐츠가 제공한 명령을 그대로 등록하지 않는다. 각 명령은 최대 600초다.
실행 로그는 무시되는 `.harness/`에 남으며 Git에는 요약 기억만 저장한다.
finish 성공 시 상태는 committed이며, publish가 작업 브랜치만 origin에 push하고
dev 대상 PR을 확인하면 completed로 바뀐다. origin의 fetch URL과 실제 단일 push URL,
--repo가 일치해야 한다. 여러 push 대상이나 다른 저장소로 향하는 pushurl은 거부한다.
publish는 실패 시 committed를 유지하므로 재시도할 수 있고, 이미 열린 동일 PR은 재사용한다.
사람이 닫거나 병합한 PR을 자동으로 다시 열지 않는다. gh 설치·로그인이 필요하다.
PR 본문은 변경 이유/검증/남은 위험을 담은 UTF-8 파일로 미리 작성한다.
최종 PR URL/번호/HEAD는 .harness/task.json에 저장한다. PR 생성 후 tracked 기억을
수정해 승인한 커밋을 바꾸지 말고, 원격 결과는 다음 작업에서 Git/gh와 대조한다.
test는 의미상 브랜치이며 자동 병합하지 않는다. dev → main 승인/병합은 사람이 맡는다.

## 루프와 중단

PostToolUse는 변경 snapshot을 감지해 최신 리뷰/검증이 필요함을 알린다.
활성 작업이 끝나지 않았으면 Stop은 최대 3회 자동 후속 작업을 요청한다.
상한을 넘으면 blocked로 남기고 커밋하지 않는다. 모든 사용자 대화에 루프를 걸지 않는다.
작업이 등록되어 있지 않으면 Stop은 통과한다.

```bash
python3 scripts/harness.py block --reason '필요한 외부 환경이 없어 검증 불가'
python3 scripts/harness.py resume
```

커밋 전 중단은 state.md에 장애물과 다음 행동을 남긴다. active 작업의 resume은
기존 내부 리뷰/검증을 무효화한다. committed 단계에서는 tracked 파일을 바꾸지 말고
block으로 .harness/task.json에 사유만 기록한다. resume 후 publish를 재시도한다.
커밋 후 소유 파일을 수정했다면 `python3 scripts/harness.py reopen`으로 active 상태를
복원하고 기억 갱신 → 내부 리뷰 → verify → finish → publish를 반복한다.
기존 커밋은 보존하고 후속 커밋을 추가한다. 이미 완료한 PR은 reopen 대상이 아니다.
프로세스 강제 종료 직전의 마지막 변경까지 기억에 저장된다고 보장하지 않는다.
새 세션에서는 기억과 실제 diff를 다시 대조한다.

## 보장 범위

스냅샷에는 HEAD, index, 무시되지 않은 파일의 내용/모드가 포함된다.
변경 후 오래된 리뷰나 검증으로 finish할 수 없다. 작업 범위 밖 변경과
기존 스테이징은 거부한다. state.md를 갱신해야 커밋할 수 있다.
리뷰 판단 자체는 독립 에이전트 결과를 Coordinator가 확인하는 절차적 게이트다.
하네스는 같은 작업 트리의 동시 수정이나 직접 git commit을 막는 보안 경계가 아니다.
검토/커밋 중 다른 프로세스가 수정하지 않도록 작업 트리의 쓰기 소유자를 하나로 유지한다.
Git 커밋 훅이 파일을 수정하면 생성된 커밋 tree와 작업 트리를 비교해 blocked로 남긴다.
이미 생성된 커밋을 자동으로 되돌리지 않는다. index/커밋을 확인하고 다시 리뷰/검증한다.
실패 후 스테이징이 남았다면 `git diff --cached`를 확인하고 해당 작업 파일만
`git restore --staged -- <파일 목록>`으로 해제한다. 파일 내용은 유지된다.

## 하네스 검증

```bash
python3 -m unittest discover -s scripts/tests -v
```

애플리케이션 테스트와는 별도다. 초기 상태의 애플리케이션 검증을 통과했다고
간주하지 않는다. 테스트 환경 격리가 다음 백로그 작업이다.
