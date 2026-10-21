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
- 옵시디언: 사용자 승인으로 앱·포트폴리오·하네스 설명의 고정 11문서로 확장한다. 작업 내역·PR 상태는 별도 하네스 기억에 보존한다.

## 현재 작업과 다음 행동

- 현재 작업: feature/capacity-test. 처리량 한계 탐색 k6(`k6/capacity.js`, 4개 시나리오)와 측정 결과 기록. 인증은 BCrypt CPU 바운드로 초당 20~30회 사이, 접수 흐름은 반복 약 800~1000/s 사이에서 꺾임(머신 전체 포화).
- 기준: 2026-10-21 dev bf45ee1(PR #63 병합), 사용자 변경 없는 작업 트리.
- 완료 기준: [작업 기록](tasks/capacity-test.md), k6 inspect, 문서 검사, 독립 리뷰, dev PR. 측정은 이 머신에서 시나리오별 1~2회 실행했다.
- 다음: 사람이 PR을 승인·병합한다. 후속 후보: 인증 동시 처리 상한(과부하 시 빠른 거절), 접수 흐름 한계 원인 분리 측정.

## 남은 한계

- 옵시디언은 PR #60에서 설명 원본 11개로 정리·동기화했고 원 요구사항과 사용자 영역 보존 및 재실행 변경 0을 확인했다. 이후 변경은 고정 export 목록에 따라 반영한다.

- 환율은 설정 예시이며(실제 공급처는 미연동으로 결정) 실시간 시세·실제 금융기관 송금·다른 시스템의 데이터 이전은 미구현이다(스키마는 Flyway로 관리).
- 멱등성 키는 접수의 성공 응답만 24시간 재현한다(실패 응답 미저장, 요청 본문 전체 비교 없음). 잠금·복구 검증은 H2와 PostgreSQL 16(Testcontainers·로컬 k6)에서 했으며 실제 운영 환경 근거는 없다.
- 실제 배포·CI/CD는 요청 범위 밖이다. GitHub AI 자동 리뷰 연결과 서버 측 브랜치 보호 강제 여부는 별도 확인이 필요하다.
