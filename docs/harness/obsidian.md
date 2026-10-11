# 옵시디언 프로젝트 연결과 단방향 동기화

코드 저장소의 선별된 Markdown이 원본이며 옵시디언 프로젝트 노트는 읽기용 요약이다.
개인 Vault 전체를 검색하거나 개인 노트를 저장소로 가져오지 않는다.
새 개발 목표 확정과 코드 재작성은 별도 작업이다.

## 연결

사용자가 Vault와 그 안의 **전용 프로젝트 폴더**를 선택한다. Vault 루트 자체를
지정하지 않는다. 예시 구조는 `<Vault>/Projects/GlobalRemittance`다.
`Projects` 부모는 사용자가 선택한 위치에 명시적으로 만들고, CLI는 configure 시
프로젝트 폴더를 생성하지 않는다. 프로젝트 폴더 생성은 최초 sync 때만 수행한다.

저장소 루트에서 다음 명령을 실행한다. 실제 개인 경로를 명령 예시, PR 본문,
추적 문서에 복사하지 않는다. CLI 인자는 로컬 shell 기록에 남을 수 있다.

```sh
python3 scripts/obsidian_sync.py configure --project-root '<선택한 절대 프로젝트 경로>' --repo ssipflow/Moin-GlobalRemittance
python3 scripts/obsidian_sync.py status
python3 scripts/obsidian_sync.py dry-run
python3 scripts/obsidian_sync.py sync
```

경로는 Git에서 제외된 `.harness/obsidian.json`에만 저장한다. status와 결과 보고는
경로를 출력하지 않는다. 미설정 status는 `not-configured`이며, 미설정 동기화는 실패한다.
프로젝트 경로와 모든 부모에 symlink를 허용하지 않는다. 코드 저장소 내부/상위와
사용자 홈 자체를 대상으로 삼을 수 없다. 설정 변경은 configure를 다시 실행한다.

이 CLI는 지정 폴더에서만 정해진 노트를 읽고 쓴다. 이 범위 검사와 symlink 방어는
**프로세스 전체의 OS 파일 접근 제한을 제공하지 않는다**. Codex가 Vault 외부 쓰기
권한을 요청하면 지정된 프로젝트 경로에 대한 권한만 검토한다. 개인 Vault의 다른
노트에 대한 읽기/쓰기 허가로 확대하지 않는다. iCloud 접근 권한도 별도다.

## 생성되는 노트

- `프로젝트 개요.md`: `docs/harness/state.md`의 제목·항목 요약과 커밋 기준 원본 링크.
- `작업/<task-id>.md`: 현재 `.harness/task.json`의 id/branch와 일치하는 **추적된**
  `docs/harness/tasks/<task-id>.md`의 요약과 원본 링크.
- `결정/<decision-id>.md`: 추적된 `docs/decisions/<소문자-영숫자-하이픈>.md`의 요약과 링크.

새 작업 문서가 아직 untracked이면 작업 노트는 생성하지 않는다. 정상 하네스
커밋 후 sync하면 작업 노트를 포함한다. 실제 요약은 현재 작업 트리를 사용하며,
GitHub 링크는 현재 HEAD 기준이므로 커밋 전 수정과 링크 내용이 다를 수 있다.
불일치는 각 노트에 표시한다. 출력은 문서당 제목/항목 16개, 2,400자 이내로 제한한다.
코드 fence 내용, 코드 파일, 실행 로그, 대화 원문, 임의 파일은 내보내지 않는다.
추적된 선별 문서 자체에도 비밀이나 개인 내용을 기록하지 않는 원칙은 유지한다.

실행 근거는 하네스의 현재 branch/HEAD/깨끗한 작업 트리/committed snapshot과
내부 리뷰·검증 근거가 일치하는 경우에만 확정 커밋으로 표시한다. PR 링크도
publish 결과의 repo/head/base/number/URL이 정확하게 일치해야 한다.
오래되거나 실패한 근거는 표시하지 않는다. GitHub의 현재 열림/승인/병합 상태를
조회하는 기능은 없으며 PR 링크는 마지막 publish 당시의 확인 결과다.
이전 작업·결정 노트는 역사 기록으로 보존하며 자동 삭제하지 않는다.

## 개인 작성 내용 보존

새 노트에는 다음 관리 구역을 생성한다. 사용자는 이 구역 **밖에** 메모한다.
관리 구역 안에서 수정한 내용은 다음 sync 때 원본 요약으로 갱신된다.

```markdown
<!-- codex:globalremittance:start -->
자동 생성 요약
<!-- codex:globalremittance:end -->
```

이미 같은 이름의 파일이 있지만 관리 구역이 없거나, marker가 중복/역순이면
덮어쓰지 않고 실패한다. 사용자 파일을 에이전트 파일로 바꾸려면 내용을 백업한 뒤
사용자가 명시적으로 정확히 한 쌍의 marker를 배치한다. CLI는 우회 파일을 만들지 않는다.
관리 구역 밖의 텍스트는 그대로 유지하며, 동일 내용의 반복 sync는 파일을 쓰지 않는다.

## 실행 시점과 실패 복구

작업 시작, 중단, 커밋 전/후, PR 생성 후에 Coordinator가 **명시적으로** dry-run/sync를
실행한다. watcher와 자동 훅은 없고 기존 하네스 코드는 변경하지 않는다.
특히 PR 생성 후 sync는 추적 문서를 고치지 않아 커밋 snapshot을 유지한다.
옵시디언 연결과 동기화는 Vault의 git commit/push 또는 Obsidian Sync/iCloud 설정을
수행하지 않는다. 기존 Vault 백업/동기화 방식을 그대로 사용한다.

결과는 `.harness/obsidian-report.json`에 저장하고 추적 작업 기억에 개인 경로를
기록하지 않는다. 오류 출력은 경로/원시 예외를 생략한다. status는 마지막 실행의
유형과 개수만 보고한다. dry-run은 Vault에 쓰지 않으며 로컬 결과 보고는 갱신한다.

한 저장소에서 동시 CLI 실행은 로컬 lock으로 거부한다. 렌더링 전후 snapshot과
현재 task가 바뀌면 실패한다. 작업 중 저장소 작성자는 한 명이라는 하네스 원칙을
유지하며 sync 중 옵시디언 수동 편집/iCloud 동시 갱신을 피한다. 대상 디렉터리는
no-follow descriptor로 접근하며 모든 기존 노트의 marker를 쓰기 전에 검사한다.
파일별 임시 파일 → atomic rename → 읽기 확인을 사용한다. 전체 노트 집합의
트랜잭션/rollback은 제공하지 않으므로, IO 오류로 일부만 갱신되면 문제 해결 후
같은 sync를 재실행한다. 외부 편집 경쟁을 완전히 차단하는 파일 잠금은 아니다.

검증은 실제 개인 Vault 대신 임시 저장소/임시 Vault에서 경로 경계, symlink,
관리 marker, 개인 메모 보존, 멱등성, 오래된 실행 근거 차단을 회귀 검사한다.
