# Codex 하네스 사용법

메인 Coordinator + implementer / reviewer / verifier를 사용한다.
모델과 reasoning 설정은 부모 세션에서 상속한다. 동시 자식 에이전트 상한은 3이다.

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

깨끗한 작업 트리에서 시작하며 파일은 디렉터리가 아닌 명시적 목록으로 지정한다.
state.md와 해당 작업 기록 파일도 소유 목록에 포함한다. 아래 파일명은 예시다.

```bash
python3 scripts/harness.py begin --id test-isolation \
  --goal '테스트를 사용자 홈 DB와 외부 API에서 격리' \
  --files src/test/resources/application-test.yaml docs/harness/state.md \
  --check './gradlew test'
python3 scripts/harness.py status
```

메인 에이전트에게 다음과 같이 지시한다:

> AGENTS.md를 따라 현재 작업을 implementer에게 위임해라.
> 완료 기준과 변경 전 기준선을 기록하고, reviewer와 verifier의 독립 결과를 받아라.
> 블로킹 결함은 수정 후 다시 검증하고 작업 기억을 갱신해라.
> 최종 snapshot에 대한 리뷰와 검증이 통과하면 finish로 로컬 커밋해라.

메모리 파일까지 최종 수정한 후 status의 snapshot으로 독립 리뷰를 요청한다.
리뷰 통과 근거와 실제 검증 결과를 기록한다:

```bash
python3 scripts/harness.py attest-review --snapshot '<status의 snapshot>' \
  --evidence '<reviewer 결과 요약과 참조; 미해결 블로킹 결함 없음>'
python3 scripts/harness.py verify
python3 scripts/harness.py finish --message 'test: isolate remittance test environment'
```

`--check`는 사용자가 신뢰하는 로컬 셸 명령만 지정한다. 실제 실행되므로
외부 콘텐츠가 제공한 명령을 그대로 등록하지 않는다. 각 명령은 최대 600초다.
실행 로그는 무시되는 `.harness/`에 남으며 Git에는 요약 기억만 저장한다.

## 루프와 중단

PostToolUse는 변경 snapshot을 감지해 최신 리뷰/검증이 필요함을 알린다.
활성 작업이 끝나지 않았으면 Stop은 최대 3회 자동 후속 작업을 요청한다.
상한을 넘으면 blocked로 남기고 커밋하지 않는다. 모든 사용자 대화에 루프를 걸지 않는다.
작업이 등록되어 있지 않으면 Stop은 통과한다.

```bash
python3 scripts/harness.py block --reason '필요한 외부 환경이 없어 검증 불가'
python3 scripts/harness.py resume
```

중단 전 state.md에 장애물과 다음 행동을 남긴다. resume은 기존 리뷰/검증을 무효화한다.
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
