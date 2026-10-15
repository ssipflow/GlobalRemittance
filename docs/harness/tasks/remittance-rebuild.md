# remittance-rebuild

- 요청: 옵시디언 Goals Of Project 기반 처음부터 구현, 기존 애플리케이션 전면 교체, 한글 주석.
- 요구사항: docs/requirements/project-goals.md. 결정: docs/decisions/remittance-rebuild.md.
- 소유: implementer는 src 전체와 build.gradle, Coordinator는 문서·지침·.gitignore.
- 완료 기준: 5개 API, JSON 오류, 민감정보 보호, 토큰/견적 만료, 금액 반올림과 수수료 경계,
  KST 날짜, 소유권·중복·동시 한도 회귀 검증, 실행 README, 내부 리뷰/실제 빌드·검증 후 dev PR.
- 기준선: 기존 테스트는 홈 파일 DB/실환율 의존으로 실행하지 않는다. 기존 통과를 주장하지 않는다.
- 검증 계획: Gradle clean check bootJar, 하네스 회귀 검사, diff check, 독립 reviewer/verifier.
- 제한: 환율 공급은 설정 어댑터이며 외부 실시간 제공처는 아직 지정되지 않았다.
- 종료: 작업 브랜치 push와 dev PR 후 옵시디언 노트 갱신. 승인·병합은 사람.

## 내부 개선 루프

- 예비 리뷰: 지연 로딩 프록시의 식별자 필드 직접 접근을 getter로 수정한다.
- JSON 계약: 기본 HTML 오류 페이지를 JSON 전용 /error 컨트롤러로 교체하고 검증한다.
- 작업 범위에 JsonErrorController.java를 추가했다. 요구사항인 모든 오류 JSON을 위한 보완이다.

## 검증 결과와 회고

- Java 17에서 기본 앱 테스트 26개와 동시 가입/로그 비노출 추가 1개 통과. 실행 JAR 빌드 확인.
- 최종 snapshot에서 전체 Gradle 테스트·bootJar, 하네스 회귀와 diff 검사를 독립 재실행한다.
- Kotlin 대신 현재 Java 17 환경을 선택했다. 사용 사례는 외부 구현 대신 포트에 의존한다.
- 예비 리뷰 결함: JPA 프록시 식별자 getter, JSON /error, SQL 제약 오류 이메일 로그 차단 반영.
- 진단 비용: SqlExceptionHelper 원시 로그 차단으로 DB 상세 로그가 줄어든다.
- 원본 예시 불일치는 문서화했고, 환율은 실제 시장 시세가 아닌 설정 어댑터로 남긴다.
- 제한: 전체 이력 기반 집계의 규모 한계, 운영 DB/시세/실제 송금/마이그레이션은 별도 작업.

## 포맷과 린트 보완

- 사용자 지적: 새 구현에 자동 포맷과 린트 검증이 빠져 있었다.
- implementer 소유 범위에 .editorconfig와 config/checkstyle/checkstyle.xml을 추가한다.
- Spotless로 전체 Java를 정리하고 Checkstyle을 main/test에 적용한다.
- 하네스 검증 명령을 clean check bootJar로 변경해 커밋 전 위반을 차단한다.

- 작업 중 별도로 발생한 .codex/hooks.json 변경은 JSON 의미가 동일한 포맷 변경이다.
  소유 범위 밖이므로 경로 한정 stash로 보관하며 커밋·PR 이후 원형을 복원한다.

- Java 17 spotlessApply check bootJar 성공: 전체 앱 27개, 포맷 및 main/test lint 통과.
- 하네스·옵시디언 회귀 44개 통과. 최종 메모리 포함 snapshot에서 gate를 재실행한다.
