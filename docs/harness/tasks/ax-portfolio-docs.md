# ax-portfolio-docs

- 요청: 최신 코드·하네스 분석에 이어 제안한 AX 포트폴리오 문서화를 진행한다.
- 시작: 2026-10-20 dev 69d2138(PR #58 로컬 병합), 깨끗한 작업 트리에서 feature/ax-portfolio-docs 생성.
- 범위: README, docs/portfolio 3문서, 현재 앱 문서의 검증 범위, engineering·harness의 오래된 종료 절차, state와 이 기록. 앱·스크립트·API 동작 변경 없음.
- 구성: AX 역할·snapshot 통제와 한계 / DB 무응답·커밋 응답 유실·own 사례 / 환경·명령·관찰·한계를 구분한 검증 표.
- 완료 기준: 근거 코드·작업 기록과 일치, 허위 기여·생산성 수치 없음, 과거 기록 보존, 상대 링크 확인, 독립 리뷰·등록 검증·커밋·dev PR.
- 등록 검증: `git diff --check`, `python3 -m unittest discover -s scripts/tests -v`.
- 새 앱·PostgreSQL·k6 검증은 수행하지 않는다. 기존 XML과 작업 기록 수치는 과거 관찰로 표기한다.
## 구현과 1차 확인

- README에 AX 목적과 대표 사례 입구를 추가하고 portfolio 3문서에 역할·통제·수정 전후·검증 환경과 한계를 구성했다.
- 현재 앱 문서의 다중 인스턴스·부하·재시작·네트워크·커밋 응답 유실 설명을 코드와 맞췄다. 과거 작업 기록은 보존했다.
- 운영 지침을 최신 workflow의 작업 브랜치 push·dev PR 종료, 사람 승인·병합으로 통일했다.
- 1차 독립 리뷰: snapshot dc51fc35에서 차단 결함 없음. 기록된 사람/Coordinator 결정, 같은 JVM의 앱 컨텍스트 2개, k6 응답 중심 검증과 DB 직접 검증의 차이를 확인했다.
- 1차 등록 verify: `git diff --check` exit 0, Python unittest 53개·44.342초·OK(exit 0). 독립 verifier가 검증 전후 snapshot 일치 확인.
- 별도 상대 링크 검사: 로컬 Markdown 경로 57개와 제목 앵커 4개, 누락 0. 앱·PG·k6 테스트는 미실행.
- 옵시디언 dry-run 성공: 선별 앱 문서 4개 변경 예정. 커밋·PR 후 sync하고 원본 일치 확인한다. 새 portfolio 문서는 export 대상이 아니다.
- 이 기록을 포함한 최종 snapshot의 독립 리뷰·등록 verify를 다시 거친 뒤 finish·publish한다. 원격 결과와 후속 동기화 결과는 로컬 상태/보고서에 기록하고 최종 응답으로 전달한다.
