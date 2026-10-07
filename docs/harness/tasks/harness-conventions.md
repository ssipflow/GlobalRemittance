# 클린 아키텍처 지침과 브랜치 정책

## 사용자 요구와 완료 기준

- 안정적인 공통 지침과 변경이 잦은 개인 성향 지침을 Markdown으로 분리.
- 클린 아키텍처를 구현/리뷰 기준으로 지정. 현재 코드 전체를 이번 작업에서 이전하지 않음.
- dev에서 feature/hotfix 분기 → test 검증 → 같은 작업을 dev 병합 → dev에서 main PR.
- main/test는 운영/테스트 배포 원본이라는 가정만 적용. CI/CD는 만들지 않음.
- 실제 로컬 dev/test/작업 브랜치 생성, 작업·커밋의 브랜치 규칙 검증.

## 변경과 결정

`docs/engineering/principles.md`에 내부로 향하는 의존성과 도메인/유스케이스/
포트/어댑터 경계, 회귀 검증, 작업 계약을 정의했다.
`preferences.md`에는 확인한 개인 선호만 기록하고 명시적인 새 선호에 따라 갱신한다.
`branches.md`에는 브랜치 역할과 승격 조건을 정의했다. AGENTS.md가 세 파일을 읽도록 한다.

test 전체를 dev에 병합하면 다른 검증 후보까지 포함될 수 있으므로 test에서
검증한 같은 작업 SHA를 dev에 병합한다. dev 통합 결과도 다시 검증하고
main PR의 최종 dev SHA를 기록한다. 승격은 별도 요청된 작업으로 진행한다.

현재 main `ba399e6`에서 dev/test를 만들고 dev에서 feature/harness-conventions를 분기했다.
원격 push/PR/승격은 하지 않았다. 기존 main 커밋이나 원격 이력을 바꾸지 않았다.

하네스는 begin에서 브랜치 이름과 현재 로컬 dev 계보를 검사하며 작업 브랜치를 저장한다.
snapshot에 브랜치 이름을 포함해 같은 HEAD라도 브랜치를 바꾸면 승인을 재사용하지 못한다.
review/verify/resume/finish는 등록한 브랜치에서만 실행한다. block은 다른 브랜치에서도 가능하다.
정책 변경 전 begin으로 등록한 이번 작업 상태만 새 필드로 이전했다.

## 검증과 남은 작업

검증 명령은 `python3 -m unittest discover -s scripts/tests -v`와 `git diff --check`.
기존 12개 외에 보호 브랜치/잘못된 이름, detached HEAD, dev 계보 누락,
같은 HEAD의 다른 브랜치, 정상 hotfix 완료를 검증한다. 총 17개다.
독립 리뷰 및 verifier 결과는 하네스의 최신 snapshot 증거로 확인한다.
현재 훅 JSON은 바꾸지 않았다. 최초 설정의 훅 신뢰 절차는 여전히 필요하다.
다음 코드 개선은 테스트 환경 격리 후 클린 아키텍처로 기능 단위 이전이다.
