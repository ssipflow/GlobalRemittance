# 옵시디언 애플리케이션 문서 연결

옵시디언에는 앱의 구조, API 계약과 주요 설계 선택을 정리한다. 작업 이력·하네스 상태·PR 메타데이터는 export하지 않는다. 원본은 코드와 함께 검토하는 `docs/application`의 선별된 문서다.

## 연결과 실행

사용자가 선택한 전용 프로젝트 폴더를 연결한다. 개인 Vault 전체를 읽거나 저장소로 가져오지 않는다. 실제 개인 경로는 Git에서 무시되는 `.harness/obsidian.json`에만 저장한다.

```sh
python3 scripts/obsidian_sync.py configure --project-root '<선택한 절대 프로젝트 경로>' --repo ssipflow/Moin-GlobalRemittance
python3 scripts/obsidian_sync.py status
python3 scripts/obsidian_sync.py dry-run
python3 scripts/obsidian_sync.py sync
```

Vault 루트·사용자 홈·코드 저장소 내부/상위 경로를 대상으로 삼지 않는다. 프로젝트 부모는 존재해야 하며 프로젝트 폴더는 최초 sync에서 생성한다. symlink는 거부한다. CLI의 경로 제한은 OS 파일 접근 격리를 대신하지 않는다.

## 생성 문서

| 저장소 원본 | 옵시디언 노트 | 내용 |
| --- | --- | --- |
| docs/application/overview.md | 프로젝트 개요.md | 서비스 목적·범위·기술·문서 안내 |
| docs/application/architecture.md | 아키텍처.md | 계층·의존성·처리 흐름·저장 구조 |
| docs/application/api.md | API 문서.md | 인증·필드·요청/응답 예시·오류 계약 |
| docs/application/design.md | 주요 설계.md | 금액·시간·동시성·보안의 근거와 비용 |

위 네 경로 중 추적된 문서만 내보낸다. 신규 untracked 문서는 정상 커밋 후 전달한다. 본문과 표·JSON/Mermaid fence를 보존해 API 계약을 잘라내지 않는다. 문서당 크기 상한을 넘으면 실패하며 소스 코드·원시 로그·임의 Markdown은 수집하지 않는다. 문서 자체에 실제 개인정보와 비밀 값을 기록하지 않는다.

원본 링크는 HEAD 기준이다. 커밋 전 동기화는 현재 작업 트리 내용과 링크의 차이를 표시하므로 최종 문서는 커밋·PR 후 sync한다. PR 번호나 검증된 커밋을 작업 기록처럼 본문에 자동 추가하지 않는다.

## 사용자 내용과 기존 노트

```markdown
<!-- codex:globalremittance:start -->
자동 갱신 앱 문서
<!-- codex:globalremittance:end -->
```

기존 노트의 관리 구역 밖 텍스트는 그대로 보존한다. marker가 없거나 중복·역순이면 덮어쓰지 않고 실패한다. Goals Of Project 원본도 변경하지 않는다. 과거 `작업/`와 `결정/` 노트는 자동 갱신 대상에서 제외하고 삭제하지 않는다. 필요한 정리는 사용자의 별도 요청으로 진행한다.

## 실행과 복구

구조·API·설계가 바뀌면 해당 원본 문서를 갱신하고 내부 검토 후 커밋·PR 생성 뒤 dry-run/sync한다. 작업 시작·중단마다 작업 로그를 Vault에 추가하지 않는다. watcher·자동 훅·양방향 동기화·Vault Git 작업은 없다.

결과는 무시되는 `.harness/obsidian-report.json`에 저장한다. 오류 출력은 개인 경로와 원시 예외를 생략한다. 동시 CLI는 lock으로 거부하고 렌더링 전후 snapshot 변경도 거부한다. 전체 기존 대상의 marker를 쓰기 전 검사한다. 파일별 atomic rename과 읽기 확인을 수행하며 동일 내용의 반복 sync는 쓰지 않는다.

전체 노트 집합의 트랜잭션/rollback은 제공하지 않는다. 일부 IO 실패가 있으면 원인을 해결하고 재실행한다. 외부 편집 경쟁을 완전히 잠그지 않으므로 sync 중 수동 편집을 피한다. 검증은 임시 저장소/Vault에서 콘텐츠 완전성, 경계·symlink·marker, 사용자 내용 보존과 멱등성을 검사한다.
