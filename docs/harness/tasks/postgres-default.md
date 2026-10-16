# postgres-default

- 배경: 사용자 요청 "data는 그대로 남겨두고, 기본 프로파일을 PostgreSQL로 바꾸고 docker-compose로 구성". 백로그 후보 2(운영 DB 검증)와는 별개의 설정 전환이다.
- 계약:
  1. 프로파일 미지정 시 `postgres`(spring.profiles.default). `h2`/`local`/`test` 지정 시 기존 H2 설정 유지. `./data` 불변.
  2. `docker-compose.yml`: postgres:16-alpine, 127.0.0.1 바인딩, healthcheck, 볼륨, `REMITTANCE_DB_PASSWORD` 필수.
  3. 앱 코드·API·오류 계약 불변. 기본 `check`는 Docker 없이 통과한다.
  4. README·docs/application(overview/architecture/design) 실행 안내를 새 기본에 맞춘다.
- 완료 기준: DefaultProfileTest(기본=postgres, h2 지정=H2 파일), check·bootJar·compose config 통과, 실제 compose DB로 기본 실행 확인, dev PR.
- 종료: 커밋·push·dev PR 후 옵시디언 sync(앱 문서 3종 변경). 승인·병합은 사람.

## 구현 결과와 직접 확인
- 변경: application.yaml(기본 프로파일·주석), docker-compose.yml, DefaultProfileTest, 문서. 운영 코드(java) 변경 없음.
- 수동 확인(임시 비밀번호·키, 확인 후 `down -v`로 컨테이너·볼륨 삭제): `up -d --wait`로 healthy, 프로파일 없이 jar 실행 시
  로그 "No active profile set, falling back to 1 default profile: postgres", PostgreSQL 16.15에 Flyway V1·V2 적용,
  가입→로그인→견적→접수→이력(todayTransferCount 1) 성공. DB를 멈추고 기동하면 SQLState 08001 안내 블록으로 종료.
  비밀번호가 비면 compose가 필수 변수 안내로 중단. 이 과정에서 안내 문구의 `예: `가 YAML 매핑으로 해석되는 오류를 발견해 값을 따옴표로 감쌌다.
- 한계: 컨테이너 재생성 후 볼륨 데이터 유지와 비밀번호 변경 시 인증 실패는 문서화만 했고 이번에 직접 재현하지 않았다.
  시작 실패 안내(DatabaseConnectionFailureAnalyzer)는 compose 명령을 언급하지 않는다(코드 변경이라 범위 밖).
- 독립 리뷰: 차단 결함 없음. 반영: design.md 잠금 대기 서술에 PostgreSQL 병기, README에 DB 사용자 일치 안내, 테스트 주석에 환경변수 전제.
  **후속(소유 파일 밖)**: `application-postgres.yaml` 1행의 "기본(H2) 설정 위에 덮어쓴다"와 `application-local.yaml` 머리 주석(local만 지정하면 H2, `postgres,local` 조합)을 새 기본에 맞게 정리,
  `DatabaseConnectionFailureAnalyzer`의 Hibernate Dialect 경로 H2 전용 안내 문구(실제 발생 가능성은 낮음), architecture.md 다이어그램의 H2 표기.
