# readme-portfolio

- 요청: 현재 README 평가에서 발견한 마이그레이션 설명·V3 누락·검증 명령·긴 분량을 수정한다.
- 기준: fetch한 origin/dev b3e84ae(PR #60 반영), feature/readme-portfolio. 사용자 변경 없음.
- 설계: README는 소개·성과·구조·빠른 실행·검증·상세 링크·한계로 축약한다. 상세 설정·H2 진단·기동 실패·DB 보존·k6·마이그레이션은 docs/application/local-guide.md에 보존한다.
- 실행 가이드는 export 고정 11개에 추가하지 않고 기존 설명 노트에서 HEAD 기준 저장소 링크로 연결한다.
- 현재 설명에서 H2의 baseline 0과 PostgreSQL baseline-on-migrate false를 구분하고 버전 표 V3을 포함한다. 포맷 변경 명령은 검증 명령과 분리한다.
- 검증 범위: git diff --check를 harness에 등록. 독립 reviewer의 코드/설정/SQL 대조, verifier의 링크·앵커·EOF와 등록 verify. 코드 변경이 없어 앱·PG·k6·Python 테스트 새 실행은 하지 않는다.
- 완료 기준: 최종 snapshot 독립 리뷰·등록 검증 후 finish·publish, 실제 dev PR URL 확인과 기존 11노트 sync/재실행 변경0 확인. 승인·병합은 사람.

## 작성 결과

- README를 268행에서 83행으로 축약했고 상세 설명은 새 로컬 가이드 176행으로 옮겼다. 관련 원본 문서의 기존 README 앵커 참조를 바꿨다.
- V3 버전 표, H2 기준선과 PostgreSQL 거부 정책, 검증과 선택적 포맷 수정 명령을 구분했다. 설정·SQL·Gradle은 읽기만 했다.
- 작성자 자체 링크·앵커·EOF 검사와 diff 검사 통과. 최종 독립 리뷰·등록 verify는 이 기록을 포함한 snapshot에서 수행한다.
- 앱·PG·k6·Python 회귀 테스트는 실행하지 않는다. PR 뒤 선별 11노트 동기화와 재실행 변경0을 확인하고 실제 결과는 로컬 보고서/최종 응답으로 남긴다.
