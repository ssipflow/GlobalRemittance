# 현재 상태

## 목표와 기준

- 목표: Goals Of Project 기반 개인 송금 서비스를 정확하고 재현 가능한 포트폴리오로 발전시킨다.
- 기술·구조: Java 17 / Spring Boot / JPA / embedded H2, com.nelson.remittance의 domain/application/adapter/bootstrap.
- 요구사항: [프로젝트 목표](../requirements/project-goals.md). 앱 설명: [문서 원본](../application/overview.md).
- 실행 범위: dev 기반 feature/hotfix → 내부 리뷰·검증 → 커밋·push → dev PR. 승인·병합은 사람.
- 설명 주석은 한글로 작성한다. 공통 원칙·개인 선호와 최신 실행 범위는 engineering 및 .codex/instructions/workflow.md를 따른다.

## 확인된 구현과 검증

- 확인 기준: 2026-10-11 dev 079e963, PR #1~#10 병합 확인. 아래는 이번 문서 작업의 시작 기준이다.
- 구현: 회원가입·로그인·견적·접수·이력 API, JSON 오류, BCrypt/AES-GCM/JWT, 금액·만료·KST 한도·소유권·중복 검사.
- 접수: 사용자 행 잠금으로 직렬화, 일일 합계는 KST 당일 구간의 DB SUM, 락 대기 초과는 503 LOCK_TIMEOUT.
- 이력 조회는 사용자 쓰기 락 없이 하나의 조회 결과에서 목록과 오늘 합계를 계산한다.
- 최근 앱 검증: PR #10 작업 커밋 40d468c에서 Java 17 clean check bootJar 통과, 로컬 XML 30개 성공·실패/건너뜀 0.
- Python 하네스·옵시디언 검증: 이전 주석 작업에서 46개 통과. 이번 기억 정리에서 앱/Python 테스트를 재실행한 결과로 간주하지 않는다.
- 품질 게이트: Spotless와 main/test Checkstyle을 Gradle check에 연결. 테스트는 별도 메모리 H2·고정 시간/환율 사용.
- 옵시디언: docs/application의 개요·아키텍처·API·주요 설계 네 문서만 동기화. 작업 이력·PR 정보는 내보내지 않는다.

## 현재 작업과 다음 행동

- 현재 작업: feature/concurrency-design-details. 동시성의 경쟁 시나리오·잠금·격리·실패·검증 설명을 상세화한다.
- 시작 기준: 2026-10-12 dev 0662a6f, PR #12 페이지네이션과 PR #13 앱 문서 재구성이 병합된 상태.
- 완료 기준: 주요 설계 문서를 코드·테스트와 대조, 독립 문서 리뷰·검증, dev PR 후 Vault 동기화.
- 다음: 사람이 이번 문서 개선 PR을 승인·병합한다. 이후 [검토 후보](backlog.md) 중 다음 범위를 정한다.
- 과거 작업의 상세 이력은 tasks와 Git/PR에 보존한다. 아래 검증 결과는 이번 문서 작업의 새 앱 실행 결과가 아니다.

## 남은 한계

- 환율은 설정 예시이며 실시간 시세·실제 금융기관 송금·기존 데이터 마이그레이션은 미구현이다.
- 요청 멱등성 키가 없다. 잠금·복구 검증은 H2 범위이며 운영 DB에 대한 근거는 없다.
- 실제 배포·CI/CD는 요청 범위 밖이다. GitHub AI 자동 리뷰 연결과 서버 측 브랜치 보호 강제 여부는 별도 확인이 필요하다.
