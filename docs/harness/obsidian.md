# 옵시디언 애플리케이션 문서 연결

옵시디언에는 앱의 구조, API 계약과 주요 설계 선택을 정리한다. 작업 이력·하네스 상태·PR 메타데이터는 export하지 않는다. 사용자 승인으로 앱·포트폴리오·하네스 설명의 고정 11문서를 코드와 함께 검토해 내보낸다.

## 연결과 실행

사용자가 선택한 전용 프로젝트 폴더를 연결한다. 개인 Vault 전체를 읽거나 저장소로 가져오지 않는다. 실제 개인 경로는 Git에서 무시되는 `.harness/obsidian.json`에만 저장한다.

```sh
python3 scripts/obsidian_sync.py configure --project-root '<선택한 절대 프로젝트 경로>' --repo ssipflow/GlobalRemittance
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
| docs/application/design.md | 주요 설계.md | 금액·시간·보안·스키마와 상세 문서 안내 |
| docs/application/concurrency.md | 동시성과 멱등성.md | 잠금·한도·재시도와 장애 검증 |
| docs/application/observability.md | Observability.md | 요청 ID·오류 로그·장애 조사 |
| docs/portfolio/index.md | 프로젝트 안내.md | 목적·대표 성과·읽기 순서·원 요구사항 구분 |
| docs/portfolio/ax-workflow.md | AX 개발 방식.md | AI 역할·통제 범위와 한계 |
| docs/portfolio/case-studies.md | 대표 개선 사례.md | 문제·선택·수정 전후·근거 |
| docs/portfolio/validation.md | 검증 범위와 결과.md | 환경·관찰·재현 명령·한계 |
| docs/harness/obsidian-workflow.md | 하네스 워크플로우.md | 작업 등록·검증·커밋·PR 사용법 |

위 고정 11경로 중 추적된 문서만 내보낸다. 신규 untracked 문서는 정상 커밋 후 전달한다. 본문과 표·JSON/Mermaid fence를 보존해 API 계약을 잘라내지 않는다. 문서당 크기 상한을 넘으면 실패하며 소스 코드·원시 로그·임의 Markdown은 수집하지 않는다. 문서 자체에 실제 개인정보와 비밀 값을 기록하지 않는다.

일반 Markdown 상대 링크는 원본 문서 기준으로 해석한다. 내보내는 대상은 URL 인코딩한 로컬 노트 링크로, 다른 저장소 파일은 HEAD 고정 GitHub 링크로 바꾼다. 앵커는 유지하고 코드·외부·위키 링크는 보존한다. 저장소 밖으로 나가는 상대 경로는 쓰기 전에 거부한다. 범용 Markdown 파서는 아니므로 새 링크 문법 도입 시 렌더 결과를 확인한다.

원본 링크는 HEAD 기준이다. 커밋 전 동기화는 현재 작업 트리 내용과 링크의 차이를 표시하므로 최종 문서는 커밋·PR 후 sync한다. PR 번호나 검증된 커밋을 작업 기록처럼 본문에 자동 추가하지 않는다.

## 사용자 내용과 기존 노트

```markdown
<!-- codex:globalremittance:start -->
자동 갱신 앱 문서
<!-- codex:globalremittance:end -->
```

기존 노트의 관리 구역 밖 텍스트는 그대로 보존한다. marker가 없거나 중복·역순이면 덮어쓰지 않고 실패한다. Goals Of Project 원본도 변경하지 않는다. 과거 `작업/`와 `결정/` 노트는 자동 갱신 대상에서 제외하고 삭제하지 않는다. 필요한 정리는 사용자의 별도 요청으로 진행한다. 이번 정리에서는 관측·동시성·하네스 기존 노트 3개만 원문과 해시를 로컬 백업하고, 백업 당시와 같을 때 관리 마커로 감싼 뒤 새 원본으로 동기화한다. sync 자체는 미관리 노트를 자동 입양하지 않는다. 원 요구사항은 프로젝트 안내에서 현재 계약과 구분한다.

## 실행과 복구

구조·API·설계가 바뀌면 해당 원본 문서를 갱신하고 내부 검토 후 커밋·PR 생성 뒤 dry-run/sync한다. 작업 시작·중단마다 작업 로그를 Vault에 추가하지 않는다. watcher·자동 훅·양방향 동기화·Vault Git 작업은 없다.

결과는 무시되는 `.harness/obsidian-report.json`에 저장한다. 오류 출력은 개인 경로와 원시 예외를 생략한다. 동시 CLI는 lock으로 거부하고 렌더링 전후 snapshot 변경도 거부한다. 전체 기존 대상의 marker를 쓰기 전 검사한다. 파일별 atomic rename과 읽기 확인을 수행하며 동일 내용의 반복 sync는 쓰지 않는다.

전체 노트 집합의 트랜잭션/rollback은 제공하지 않는다. 일부 IO 실패가 있으면 원인을 해결하고 재실행한다. 외부 편집 경쟁을 완전히 잠그지 않으므로 sync 중 수동 편집을 피한다. 검증은 임시 저장소/Vault에서 콘텐츠 완전성, 경계·symlink·marker, 사용자 내용 보존과 멱등성을 검사한다.
