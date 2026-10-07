# GlobalRemittance portfolio engineering

목표: 정확하고 재현 가능하며 설계 근거를 설명할 수 있는 송금 서비스.
Java 17 / Spring Boot / JPA / QueryDSL. 품질 기준과 루프는
`docs/harness/design.md`, 사용법은 `docs/harness/README.md`를 따른다.

## 지침 로딩

매 세션 `docs/engineering/principles.md`(안정적인 공통 원칙),
`docs/engineering/preferences.md`(변경이 잦은 개인 선호),
`docs/engineering/branches.md`(브랜치 정책)를 읽는다.
클린 아키텍처의 의존성 방향을 구현과 리뷰의 기준으로 적용한다.
사용자의 현재 명시적 요청을 우선하며 개인 선호로 공통 품질 기준을 완화하지 않는다.

## 세션 시작과 기억

먼저 `docs/harness/state.md`와 현재 작업에 필요한 백로그 항목만 읽는다.
`python3 scripts/harness.py status`로 실행 상태와 현재 snapshot을 확인한다.
대화 기억 대신 코드, Git 이력, 작업 기록을 대조한다. 전체 로그를 컨텍스트에 넣지 않는다.
작업 시작/중단/완료 시 state.md를 갱신하고 중요한 설계 선택은 docs/decisions에 남긴다.
비밀 값, 개인정보, 전체 대화는 기억 파일에 저장하지 않는다.

## 에이전트와 개선 루프

메인 에이전트는 Coordinator다. 코드 작업은 implementer에 위임하고,
변경 완료 후 reviewer와 verifier에 독립 검토/검증을 위임한다.
작은 문서/하네스 설정 작업은 메인이 구현해도 된다. 파일 수정은 한 명만 담당한다.
Reviewer는 읽기 전용이며 verifier는 검증만 수행한다. 자식은 커밋하지 않는다.
현재 작업, 완료 기준, 소유 파일, 관련 결정, snapshot을 전달하고 결과를 기다린다.
블로킹 결함을 수정한 뒤 새 snapshot에서 다시 검토/검증한다.
동일 실패가 반복되거나 3회 수정 후에도 실패하면 blocked로 기록한다.

## 작업과 커밋

작업 시작 전에 Git 상태를 확인하고 `harness.py begin`으로 작업 범위와 검증 명령을 등록한다.
dev에서 분기한 `feature/<feature-name>` 또는 `hotfix/<fix-name>`에서 작업한다.
승격 흐름은 작업 브랜치 → test 검증 → 같은 작업 SHA를 dev 병합 → dev에서 main PR이다.
main/dev/test에 직접 구현 커밋을 만들지 않는다. 일반 작업의 자동화는 로컬 커밋까지다.
기존 사용자 변경이 있으면 별도 작업 트리에서 진행한다. 임의로 지우거나 포함하지 않는다.
리뷰 통과는 최신 snapshot에만 기록하며 실제 통과 결과가 있을 때만 attest-review를 사용한다.
`harness.py verify`로 등록된 명령을 실행한다. 실패/건너뜀은 성공으로 취급하지 않는다.
작업 기억을 먼저 갱신하고 최종 변경 전체를 리뷰/검증한 뒤 `harness.py finish`로 자동 로컬 커밋한다.
commit 단계에서 변경이 생겼다면 다시 검증한다. 자동 push나 이력 재작성은 하지 않는다.
사용자의 중단 요청을 존중한다. 필요하면 `harness.py block --reason ...`로 루프를 종료한다.

도메인 변경은 금액/반올림, 한도, 중복/동시성, 만료/날짜 경계,
인증/소유권에 해당하는 회귀 검증을 포함한다. 테스트 환경 격리가 첫 개선 과제다.
