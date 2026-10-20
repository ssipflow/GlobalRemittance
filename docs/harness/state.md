# 현재 상태

## 목표와 기준

- 목표: Goals Of Project 기반 개인 송금 서비스를 정확하고 재현 가능한 포트폴리오로 발전시킨다.
- 기술·구조: Java 17 / Spring Boot / JPA / PostgreSQL(기본)·H2(격리 테스트/선택 실행), com.nelson.remittance의 domain/application/adapter/bootstrap.
- 요구사항: [프로젝트 목표](../requirements/project-goals.md). 앱 설명: [문서 원본](../application/overview.md).
- 실행 범위: dev 기반 feature/hotfix → 내부 리뷰·검증 → 커밋·push → dev PR. 승인·병합은 사람.
- 설명 주석은 한글로 작성한다. 공통 원칙·개인 선호와 최신 실행 범위는 engineering 및 .codex/instructions/workflow.md를 따른다.

## 확인된 구현과 검증

- 시작 기준: 2026-10-13 dev f0834e1, PR #24까지 로컬 병합 이력 확인.
- 기본 DB: 프로파일 미지정 시 PostgreSQL(docker-compose). embedded H2는 `h2` 프로파일.
- 구현: 회원·송금 API, 사용자 잠금과 일일 DB 집계, keyset 이력, 24시간 미만 성공 응답 멱등 재현.
- 후속 구현: Swagger/OpenAPI, local H2·SQL 진단, X-Request-Id와 선별 오류 로그, Flyway V1~V3, Retry-After와 잠금 설정 사전 검증.
- 기존 로컬 테스트 XML 관찰(2026-10-20): 기본 247개·PostgreSQL 40개, 실패·오류·건너뜀 0개. 현재 문서 작업의 새 앱 실행 결과나 특정 커밋의 인증으로 취급하지 않는다. 재현 범위와 과거 실험 근거는 docs/portfolio/validation.md에 정리한다.
- 품질 게이트: Spotless·Checkstyle·회귀 테스트. 기본 테스트는 격리된 메모리 H2를 사용하고 마이그레이션은 임시 DB로 검증한다.
- 옵시디언: 앱 문서 네 종류만 내보내며 작업 내역·PR 상태는 별도 하네스 기억에 보존한다.

## 현재 작업과 다음 행동

- 현재 작업: feature/ax-portfolio-docs. AX 작업 방식·대표 개선 사례·검증 범위를 포트폴리오 문서로 구성하고 최신 구현과 설명을 맞춘다. 앱·하네스 코드 변경 없음.
- 시작 기준: 2026-10-20 dev 69d2138, PR #58까지 로컬 병합 확인. 저장소 이름은 ssipflow/GlobalRemittance.
- 완료 기준: README 입구·포트폴리오 3문서, 앱 문서의 오래된 검증 범위와 운영 지침 정리, 상대 링크·근거 대조, 독립 리뷰·등록 검증, 커밋·dev PR.
- 검증: `git diff --check`, `python3 -m unittest discover -s scripts/tests -v`. 앱·부하 테스트를 새로 실행하지 않고 과거 관찰과 현재 검증을 구분한다.
- 확인: 문서 작성과 1차 내부 리뷰(차단 결함 없음)·등록 검증(Python 53개 OK, diff 정상), 상대 링크·앵커 검사를 마쳤다. 앱·PostgreSQL·k6는 새로 실행하지 않았다.
- 다음: 이 기억을 포함한 최종 snapshot 내부 리뷰·검증 → 커밋·dev PR → 선별 앱 문서 옵시디언 sync. 승인·병합은 사람. 기존 기능 백로그는 종료 상태이며 범위를 확대하지 않는다.
- 작업 기록: [AX 포트폴리오 문서](tasks/ax-portfolio-docs.md).

## 남은 한계

- 2026-10-15 사용자 요청으로 옵시디언 프로젝트 폴더에 `하네스 워크플로우.md`를 별도 작성하고 읽기·원본 일치를 확인했다. 자동 export 대상은 그대로이며, 진행 중인 구현의 리뷰·검증·커밋·publish는 이 문서 작업에서 수행하지 않았다.

- 환율은 설정 예시이며(실제 공급처는 미연동으로 결정) 실시간 시세·실제 금융기관 송금·다른 시스템의 데이터 이전은 미구현이다(스키마는 Flyway로 관리).
- 멱등성 키는 접수의 성공 응답만 24시간 재현한다(실패 응답 미저장, 요청 본문 전체 비교 없음). 잠금·복구 검증은 H2와 PostgreSQL 16(Testcontainers·로컬 k6)에서 했으며 실제 운영 환경 근거는 없다.
- 실제 배포·CI/CD는 요청 범위 밖이다. GitHub AI 자동 리뷰 연결과 서버 측 브랜치 보호 강제 여부는 별도 확인이 필요하다.
