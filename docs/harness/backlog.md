# 개선 백로그

2026-10-11 dev 079e963(PR #10 병합)을 기준으로 재분류했다.
기존 진단 항목은 전면 재구현과 후속 개선에 반영됐으므로 진행 중 과제로 취급하지 않는다.
아래 후보의 순서는 제안이며 실행 승인이 아니다. 새 작업은 사용자와 범위·완료 기준을 정한다.

## 다음 검토 후보

| 순서 | 후보 | 현재 근거 | 선택 시 완료 기준 |
| --- | --- | --- | --- |
| 1 | 실제 환율 공급처 어댑터 | 신선도 정책과 모의 공급처는 완료. 실제 외부 공급처는 사용자 결정으로 미연동 | 공급처 선정 후 타임아웃·재시도·서킷 브레이커, 트랜잭션 밖 호출, 견적에 기준 시각 저장(감사용 스키마 변경) |
| 2 | 운영 DB 검증의 남은 범위 | 단일 앱 프로세스의 PostgreSQL 16 동시성·락·연결 끊김 복구는 검증. 다중 인스턴스·고부하·DB 재시작은 미검증. 연결 끊김 직후 500은 503 `DB_UNAVAILABLE`로 완화([기록](tasks/db-unavailable-503.md)). 롤백 실패 주입은 PostgreSQL에서 검증([기록](tasks/postgres-rollback-failure.md)) | 필요한 항목을 골라 범위를 정하고 재현 가능한 통합 검증 |

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
| 요청 멱등성 키 | 접수의 선택 Idempotency-Key, 같은 키·견적 24시간 성공 재현, 다른 견적 422, 실패 비소비, 사용자 잠금 안 동시 재시도 1회 반영 | [멱등성](tasks/request-idempotency.md) |
| 스키마 마이그레이션(Flyway) | ddl-auto 대신 버전 SQL(V1 기준선·V2 멱등성 키), validate, 이력 없는 기존 파일 DB의 기준선 업그레이드와 실제 구 DB 파일 확인 | [Flyway](tasks/flyway-migrations.md) |
| 잠금 대기 안내와 설정 검증 | LOCK_TIMEOUT 503의 `Retry-After: 1`, REMITTANCE_LOCK_TIMEOUT_MS 1~30000 검증과 시작 실패 안내 | [잠금 안내](tasks/lock-timeout-guidance.md) |
| 환율 신선도 정책과 모의 공급처 | 환율 기준 시각, 허용 지연 초과·조회 실패 시 견적 503(`RATE_STALE`/`RATE_UNAVAILABLE`), 지연·장애 주입 모의 공급처, 발급 견적은 환율 장애와 무관 | [환율 정책](tasks/rate-freshness-policy.md) |
| DB 접속 실패 진단 | Flyway 단계 접속 실패를 SQLState로 분류(인증/연결 불가/DB 없음)해 비노출 안내 블록 출력, DB 종류별 조치 | [DB 접속 진단](tasks/db-connection-diagnostics.md) |
| PostgreSQL 동시성·락·복구 검증(운영 DB 검증 2단계) | 동시 접수·한도·멱등성 경쟁, 락 타임아웃(503·`Retry-After`), 55P03 직접 확인, 연결 끊김 복구를 Testcontainers로 재현 | [PostgreSQL 동시성](tasks/postgres-concurrency.md) |
| PostgreSQL 프로파일(운영 DB 검증 1단계) | `postgres` 프로파일, PostgreSQL 전용 Flyway V1/V2, `SET lock_timeout`, 태그 분리된 Testcontainers 스키마·기동·API 스모크 테스트 | [PostgreSQL 프로파일](tasks/postgres-profile.md) |
| 테스트 가드 강화 | ArchitectureTest의 Swagger/springdoc 참조 금지와 양성 대조, OpenApiTest의 swagger-ui.html 302·Location 정밀 검증 | [가드 강화](tasks/test-guard-hardening.md) |
| 개인정보 없는 오류 관측 | X-Request-Id, 예외 메시지·본문·쿼리 없이 클래스·SQLState·경로만 남기는 오류 로그, DB 접속 실패 진단, 로그 캡처 검증 | [오류 관측](tasks/error-observability.md) |
| 기본 프로파일 PostgreSQL 전환 | `spring.profiles.default: postgres`, docker-compose PostgreSQL 16, H2는 `h2`/`local`/`test`로 유지, `./data` 보존 | [기본 프로파일](tasks/postgres-default.md), [결정](../decisions/postgres-default.md) |
| 한글 설명 주석 | 직접 작성 코드·테스트·하네스·설정 주석과 지속 지침 | Git db6634b, PR #9 |

완료는 위 범위에 대한 기록이며 모든 보안·정확성·운영 검증이 끝났다는 의미가 아니다.
원래 CI/실행 문서 항목 중 로컬 검증·문서만 반영됐다. 배포·CI/CD 구축은 사용자 요청 범위 밖이며 자동 실행하지 않는다.
과거 상세 기록은 docs/harness/tasks와 docs/decisions 및 Git 이력에 보존한다.
