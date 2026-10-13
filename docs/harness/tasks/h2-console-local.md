# h2-console-local

- 배경: 사용자가 로컬에서 H2 콘솔을 켜자(커밋되지 않은 application.yaml 변경) 로그인은 되지만 이후 화면이 "연결 거부"였다.
  콘솔을 켠 앱의 `/h2-console/` 응답에 `X-Frame-Options: DENY`가 붙는 것을 확인했다. Spring Security 기본값이 콘솔의 프레임을 막는다.
  사용자의 로컬 변경은 stash로 보존했다.
- 계약: 기본 실행은 지금처럼 콘솔 꺼짐·프레임 DENY. `local` 프로파일(`SPRING_PROFILES_ACTIVE=local` 또는 `--spring.profiles.active=local`)에서만
  콘솔을 켜고, 콘솔이 켜진 경우에만 프레임 정책을 같은 출처 허용(SAMEORIGIN)으로 바꾼다. application.yaml의 기본값(`enabled: false`)은 바꾸지 않는다.
- 불변식: 프로파일 없이 실행하면 `/h2-console`은 404 JSON이고 응답 헤더는 DENY다. 콘솔 설정이 기본 설정에 새지 않는다.
- README: 콘솔 사용법(local 프로파일, 접속 정보 `jdbc:h2:file:./data/remittance`, 사용자 sa, REMITTANCE_DB_PASSWORD)과 보안 주의.
  DB 비밀번호 절은 예시가 "새 DB를 만들 때만" 쓰는 것임을 앞에 분명히 하고, 기존 DB가 있을 때의 증상·`data/` 삭제로 새로 시작하는 방법을 적는다.
- 완료 기준: 기본/로컬 프로파일별 콘솔 응답·헤더 테스트, 기존 테스트 통과, Java 17 check·bootJar, 실제 local 기동에서 콘솔 로그인 흐름 헤더 확인.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과와 직접 확인
- application-local.yaml(`spring.h2.console.enabled: true`), SecurityConfiguration(콘솔이 켜졌을 때만 frameOptions sameOrigin), H2ConsoleTest 4개.
  application.yaml 기본값(`enabled: false`)은 그대로다. 프레임 정책은 콘솔이 켜지면 전체 경로에 적용된다(경로별 분리는 범위 밖).
- 실제 `local` 프로파일 기동(메모리 DB): 콘솔 로그인 후 header/tables/query/help 프레임이 모두 200, `X-Frame-Options: SAMEORIGIN`.
  실제 브라우저에서의 화면은 확인하지 못했다.
- 같은 조건에서 파일 DB로 앱이 떠 있는 동안 콘솔이 같은 파일을 열어 REMITTANCES·USERS 테이블이 보였다(파일 잠금 충돌 없음).
- README: DB 비밀번호 소절이 `--args` 설명을 삼키던 구조를 정리하고, 예시를 "새 DB로 시작할 때만"으로 한정했다.
  기존 DB와 비밀번호가 다를 때의 증상(앱 로그는 Dialect 오류만, 원인은 data/remittance.trace.db)과 data 삭제·ALTER USER 선택지를 적었다.
- 사용자의 로컬 `application.yaml` 변경(콘솔 enabled)은 stash에 보존했다. 이 PR과 무관하다.
- README의 `BeanCreationException` 증상 서술은 사용자가 전달한 실제 오류 로그에서 가져왔다(직접 재현한 것은 아니다).
- 리뷰어 비차단 제안 반영: 콘솔의 인증 없음·임의 JDBC URL 입력 위험 서술, "충돌 없음"의 조건(같은 JVM·같은 URL) 명시.
  `webAllowOthers` 기본값은 일반 지식이며 확인하지 않았다고 README에 표기했다.
