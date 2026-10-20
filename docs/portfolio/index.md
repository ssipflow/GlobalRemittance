# 프로젝트 안내

GlobalRemittance는 원화 송금 견적·접수 서비스를 만들면서 AI 개발의 작업 분해·구현·리뷰·검증을 하네스로 관리한 개인 AX 프로젝트다. 서비스 자체에 생성형 AI 기능을 넣은 것은 아니다. 목적은 코드 생성량보다 업무 규칙과 실패 동작을 재현하고, AI가 만든 결과를 어떤 근거로 받아들였는지 설명하는 것이다.

[Goals Of Project 원 요구사항](../requirements/project-goals.md)을 출발점으로 회원·인증·USD/JPY 견적·일일 한도·접수·이력을 구현했다. 금융기관 지급과 실제 자금 이동은 없고 환율은 설정값이다. 최소 금액·이메일 정규화·멱등성 등 후속 정책은 원 요구사항과 구분해 [현재 API](../application/api.md)와 [설계](../application/design.md)를 따른다. 원 요구사항은 현재 동작 명세를 대신하지 않는다.

## 먼저 볼 성과 세 가지

| 사례 | 관찰과 결과 | 보여 주는 역량 |
| --- | --- | --- |
| DB 무응답 | 연결 획득 제한으로 기존 연결의 읽기를 제한하지 못함을 재현, 소켓 제한 도입·복구 확인 | 가설과 실험으로 원인 구분 |
| 커밋 응답 유실 | COMMIT 전·후에 결함 주입, DB 행·한도 합계·같은 키 재시도 확인 | 불확실한 성공 결과와 멱등성 이해 |
| AI 작업 범위 변경 | 수동 상태 편집을 own 명령으로 대체, 소유 범위·snapshot 게이트 유지 | AI 개발 절차의 문제 발견과 개선 |

구체적인 수정 전후와 근거는 [대표 개선 사례](case-studies.md)에 있다. 생산성 향상률은 측정하지 않았으며 모든 기술 판단을 사람이 직접 했다고 주장하지 않는다. 사람과 에이전트의 역할·자동화 한계는 [AX 개발 방식](ax-workflow.md)에 구분한다.

## 추천 읽기 순서

1. [AX 개발 방식](ax-workflow.md): 사람·Coordinator·구현·리뷰·검증 역할과 완료 게이트.
2. [대표 개선 사례](case-studies.md): 문제·가정·재현·선택·검증·한계.
3. [검증 범위와 결과](validation.md): 환경·과거 측정·재현 명령·남은 범위.
4. [아키텍처](../application/architecture.md): 의존성·트랜잭션·ERD·견적 상태.
5. [동시성과 멱등성](../application/concurrency.md): 사용자 잠금·한도 집계·실패·재시도 계약.
6. 필요에 따라 [프로젝트 개요](../application/overview.md), [API](../application/api.md), [주요 설계](../application/design.md), [Observability](../application/observability.md)를 읽는다.

직접 실행하려면 [README](../../README.md#먼저-실행하기), 하네스를 운영하려면 [하네스 워크플로우](../harness/obsidian-workflow.md)를 따른다. 과거 작업 기록은 당시 판단의 근거이며 현재 범위는 앱 문서와 검증 문서를 기준으로 본다.
