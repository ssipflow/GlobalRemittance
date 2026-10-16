# 기본 프로파일을 PostgreSQL로 전환

- 배경: 사용자가 기본 프로파일을 PostgreSQL로 바꾸고 로컬 DB를 docker-compose로 구성하길 요청했다. `./data`(H2 파일 DB)는 그대로 둔다.
- 선택: `application.yaml`의 `spring.profiles.default: postgres`. 프로파일을 지정하지 않으면 기존 `postgres` 프로파일이 적용된다.
  H2 기준 설정(파일 DB URL·기준선)은 `application.yaml`에 남기고 postgres가 활성일 때만 덮어쓴다.
- 대안: H2/PostgreSQL 설정을 뒤집어 base를 PostgreSQL로 두는 방안. 테스트·`local`·`h2`가 PostgreSQL 전용 속성(`connection-init-sql`,
  Flyway 위치)을 모두 되돌려야 하고 `{"test","postgres"}` 조합이 깨져 변경 범위가 커서 택하지 않았다.
- 비용: 프로파일을 지정하면 기본값이 대체된다. `local`만 지정하면 H2로 실행된다(기존 동작과 동일). PostgreSQL에서 진단 로그를 쓰려면 `postgres,local`.
  기본 실행에 Docker와 DB 비밀번호가 필요하다. 기본 테스트는 `test` 프로파일의 메모리 H2라 영향이 없다.
- compose: `postgres:16-alpine`(테스트와 같은 버전), loopback 바인딩, healthcheck, 이름 있는 볼륨, 비밀번호는 환경에서 필수(`:?`).
  앱은 컨테이너에 넣지 않는다(호스트 `bootRun`). 필요 이상의 인프라를 추가하지 않는다.
