# 개선 백로그

2026-10-11 dev 079e963(PR #10 병합)을 기준으로 재분류했다.
기존 진단 항목은 전면 재구현과 후속 개선에 반영됐으므로 진행 중 과제로 취급하지 않는다.
아래 후보의 순서는 제안이며 실행 승인이 아니다. 새 작업은 사용자와 범위·완료 기준을 정한다.

## 다음 검토 후보

| 순서 | 후보 | 현재 근거 | 선택 시 완료 기준 |
| --- | --- | --- | --- |
| 1 | 요청 멱등성 키 | 동일 견적 중복 거부는 있지만 재시도 키 계약은 없음 | 사용자·키 범위, 동일/다른 payload 및 동시 재시도 처리, 응답 재현과 만료 정책 검증 |
| 2 | 개인정보를 제거한 오류 관측 | SqlExceptionHelper 원시 로그 차단으로 DB 진단 정보가 감소 | 민감 값 미노출과 요청 추적, 오류 원인 관측 검증 |
| 3 | 환율 공급 어댑터 | 설정 기반 예시 환율만 제공 | 공급처·최신성·실패/지연 정책을 먼저 결정하고 Rates 포트 및 격리 회귀 검증 |
| 4 | 운영 DB의 잠금·장애 복구 검증 | embedded H2 및 URL 락 타임아웃 옵션으로 검증 | DB 선정 후 동시 한도·중복·락 타임아웃·롤백 실패의 재현 가능한 통합 검증 |

Retry-After 헤더와 락 타임아웃 환경변수 검증은 [동시성 작업](tasks/concurrency-hardening.md)의 범위 밖 제안으로 남아 있다. 확정 결함이나 승인된 작업으로 간주하지 않는다.

## 완료된 기존 개선 범위

| 기존 항목 | 반영된 내용 | 근거 |
| --- | --- | --- |
| 테스트 격리와 기준선 | 사용자 홈 DB/실제 환율과 분리한 테스트, 고정 Clock·환율과 메모리 H2 | [전면 재구현](tasks/remittance-rebuild.md), PR #5 |
| 송금 정확성·동시성 | 반올림·수수료·중복·한도·만료 회귀, 사용자 잠금, 일일 DB 집계와 락 타임아웃 처리 | [재구현](tasks/remittance-rebuild.md), [동시성 개선](tasks/concurrency-hardening.md), PR #5/#10 |
| 인증·소유권·비밀 관리 | JWT·BCrypt·AES-GCM·환경변수 키·타인 견적 차단·민감 로그 회귀 | [재구현](tasks/remittance-rebuild.md), PR #5 |
| 클린 아키텍처 이전 | domain/application/adapter/bootstrap 및 아키텍처 테스트 | [재구현](tasks/remittance-rebuild.md), PR #5 |
| 로컬 검증과 실행 문서 | README 실행 안내, Spotless/Checkstyle 및 하네스 검증 게이트 | [재구현](tasks/remittance-rebuild.md), PR #5 |
| 개인 프로젝트 이름 공간 | com.nelson.remittance로 main/test 경로·참조 및 Gradle group 변경 | [패키지 변경](tasks/nelson-package.md), PR #7 |
| 앱 이해용 문서 | 개요·아키텍처·API·설계 원본과 네 노트 동기화 | [앱 문서화](tasks/application-docs.md), PR #8 |
| 이력 페이지네이션 | 선택 size·cursor 키셋 페이지, nextCursor, 페이지 무관한 KST 오늘 집계와 경계·동시 접수 회귀 | [이력 페이지네이션](tasks/history-pagination.md) |
| 한글 설명 주석 | 직접 작성 코드·테스트·하네스·설정 주석과 지속 지침 | Git db6634b, PR #9 |

완료는 위 범위에 대한 기록이며 모든 보안·정확성·운영 검증이 끝났다는 의미가 아니다.
원래 CI/실행 문서 항목 중 로컬 검증·문서만 반영됐다. 배포·CI/CD 구축은 사용자 요청 범위 밖이며 자동 실행하지 않는다.
과거 상세 기록은 docs/harness/tasks와 docs/decisions 및 Git 이력에 보존한다.
