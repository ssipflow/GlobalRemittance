# 현재 상태

## 목표와 기준

- 목표: Goals Of Project 기반 개인 송금 서비스를 정확하고 재현 가능한 포트폴리오로 발전시킨다.
- 기술·구조: Java 17 / Spring Boot / JPA / embedded H2, com.nelson.remittance의 domain/application/adapter/bootstrap.
- 요구사항: [프로젝트 목표](../requirements/project-goals.md). 앱 설명: [문서 원본](../application/overview.md).
- 실행 범위: dev 기반 feature/hotfix → 내부 리뷰·검증 → 커밋·push → dev PR. 승인·병합은 사람.
- 설명 주석은 한글로 작성한다. 공통 원칙·개인 선호와 최신 실행 범위는 engineering 및 .codex/instructions/workflow.md를 따른다.

## 확인된 구현과 검증

- 시작 기준: 2026-10-13 dev f0834e1, PR #24까지 로컬 병합 이력 확인.
- 구현: 회원·송금 API, 사용자 잠금과 일일 DB 집계, keyset 이력, 24시간 미만 성공 응답 멱등 재현.
- 후속 구현: Swagger/OpenAPI, local H2·SQL 진단, X-Request-Id와 선별 오류 로그, Flyway V1/V2, Retry-After와 잠금 설정 사전 검증.
- 최근 검증 기록: PR #24 작업에서 Java 17 check·bootJar 및 앱 96개 통과로 기록됐다. 이번 문서 작업의 새 앱 실행 결과가 아니다.
- 품질 게이트: Spotless·Checkstyle·회귀 테스트. 기본 테스트는 격리된 메모리 H2를 사용하고 마이그레이션은 임시 DB로 검증한다.
- 옵시디언: 앱 문서 네 종류만 내보내며 작업 내역·PR 상태는 별도 하네스 기억에 보존한다.

## 현재 작업과 다음 행동

- 현재 작업: feature/postgres-profile. PostgreSQL 프로파일·전용 Flyway SQL·Docker 통합 테스트(운영 DB 검증 1단계, 사용자 선택). 동시성·장애 복구 검증은 다음 PR.
- 시작 기준: 2026-10-15 dev f1a968e, PR #26까지 병합된 상태.
- 완료 기준: [작업 기록](tasks/postgres-profile.md)의 postgresTest·기본 check·실제 컨테이너 기동 확인, 문서 갱신, dev PR 후 Vault 동기화.
- 다음: 사람이 PR을 승인·병합한다. 이후 PostgreSQL 동시성·잠금·장애 복구 검증 PR.
- 기존 상세 동시성 설명은 유지한다. 현재 항목은 작업 전환 시 교체하고 이력은 tasks/Git에 남긴다.

## 남은 한계

- 환율은 설정 예시이며 실시간 시세·실제 금융기관 송금·다른 시스템의 데이터 이전은 미구현이다(스키마는 Flyway로 관리).
- 멱등성 키는 접수의 성공 응답만 24시간 재현한다(실패 응답 미저장, 요청 본문 전체 비교 없음). 잠금·복구 검증은 H2 범위이며 운영 DB에 대한 근거는 없다.
- 실제 배포·CI/CD는 요청 범위 밖이다. GitHub AI 자동 리뷰 연결과 서버 측 브랜치 보호 강제 여부는 별도 확인이 필요하다.
