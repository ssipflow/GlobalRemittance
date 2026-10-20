# obsidian-docs-cleanup

- 요청: 옵시디언 분석에서 제안한 중복 축약·최신화·안내 추가와 ERD/상태전이를 정리한다.
- 기준: dev fcbc009, feature/obsidian-docs-cleanup. 앱 API·도메인 코드 변경 없음.
- 쓰기 소유: sync implementer는 scripts/obsidian_sync.py와 회귀 테스트, docs worker는 앱·포트폴리오·하네스 설명 문서, Coordinator는 지침·연결사용법·작업기억과 외부 전환 담당.
- 설계: 저장소 원본을 고정 export 11개로 확장하고, export 대상 상대 링크는 로컬 노트로, 그 외 저장소 경로는 HEAD 고정 GitHub 링크로 변환한다. 코드 블록은 보존한다.
- 기존 별도 관측/동시성/하네스 노트는 전용 프로젝트 안의 이름 지정 3파일만 읽고 원문 백업·일치 확인 후 관리 전환한다. Goals Of Project와 marker 밖 사용자 내용은 보존한다. 전체 Vault 검색·Git 작업 없음.
- 완료 기준: 문서와 코드 근거 일치·링크 정상·의미 있는 sync 회귀·독립 리뷰·등록 verify·커밋·dev PR·외부 sync 후 재실행/원문보존 확인.
- 등록 검증: git diff --check, Python unittest scripts/tests 전체. 앱·PG·k6 새 실행 결과를 주장하지 않는다.
## 구현과 확인

- 안내·동시성·관측·하네스 원본 4개 추가, 기존 주요설계 중복 축약, 개요 설정 표 축약, 아키텍처의 현재 트랜잭션·ERD·계산상 상태 전이 보강.
- 내보내기 고정 11개와 상대 링크 변환을 구현했다. inline/reference 일반 Markdown만 지원하며 코드·외부·위키·앵커를 보존한다. 저장소 밖 경로는 쓰기 전 거부한다.
- 구현자 담당 sync 테스트 22개 통과. 등록 전체 검증·독립 리뷰는 이 기록을 포함한 최종 snapshot에 수행한다.
- 커밋 전 실제 dry-run은 추적된 기존 7문서 정상. 새 원본 4개는 커밋 뒤 추적 상태로 export되며, 최종 verifier가 격리 fixture에서 11개 렌더를 확인한다.
- 기존 전용 프로젝트 8노트 원문·SHA-256을 Git 미추적 .harness/obsidian-cleanup-backup에 보존했다. 커밋·PR 뒤 이름 지정 3노트가 백업과 같을 때만 관리 전환하고 sync한다. marker 밖 사용자 영역·Goals Of Project는 hash/본문으로 보존 확인한다.
- 동기화 뒤 11개 관리 노트와 원본 렌더 일치·로컬 링크·외부 사용자 영역·요구사항 불변·재실행 변경 0을 확인하고 로컬 보고서와 최종 응답에 결과를 남긴다. 커밋 뒤 tracked 기억은 바꾸지 않는다.
